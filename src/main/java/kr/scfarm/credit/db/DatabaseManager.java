package kr.scfarm.credit.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MariaDB 커넥션 풀(HikariCP) 관리 + 스키마 초기화.
 *
 * <p>잔액의 단일 진실원은 MariaDB 이다. 연결이 안 되면 크레딧 연산을 수행하지 않는다(호출측에서 비활성화 처리).
 */
public final class DatabaseManager {

    private final HikariDataSource dataSource;

    public DatabaseManager(ConfigurationSection dbConfig) {
        HikariConfig hikari = new HikariConfig();
        String host = dbConfig.getString("host", "127.0.0.1");
        int port = dbConfig.getInt("port", 3306);
        String name = dbConfig.getString("name", "credit");

        hikari.setPoolName("크레딧-Hikari");
        // socketTimeout 이 없으면 방화벽 idle-drop / DB 페일오버 / 메타데이터 락 대기 시 쿼리가
        // 무한 대기한다. initSchema 는 onEnable(메인 스레드)에서 도는 만큼, 서버가 영영 부팅되지
        // 않는 사고로 이어질 수 있어 반드시 지정한다.
        hikari.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + name
                + "?connectTimeout=5000&socketTimeout=60000");
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.setUsername(dbConfig.getString("user", "root"));
        hikari.setPassword(dbConfig.getString("password", ""));
        hikari.setMaximumPoolSize(Math.max(1, dbConfig.getInt("pool-size", 10)));
        // 실패 시 빠르게 판단(비활성화 결정)하도록 짧게. 무손실 우선: 커넥션이 없으면 연산 자체를 하지 않는다.
        hikari.setConnectionTimeout(5_000L);
        hikari.setInitializationFailTimeout(5_000L);
        // 유휴 커넥션이 방화벽/wait_timeout 에 조용히 끊겨 첫 연산이 실패하는 사고 방지(PlayerPoints 계열의
        // 커넥션 드랍 이슈 교훈). 5분마다 keepalive ping.
        hikari.setKeepaliveTime(300_000L);
        // MariaDB 드라이버용 프리페어드 스테이트먼트 서버측 캐시(Connector/J 의 cachePrepStmts 류는
        // MariaDB 드라이버가 무시하므로 쓰지 않는다)
        hikari.addDataSourceProperty("useServerPrepStmts", "true");

        this.dataSource = new HikariDataSource(hikari);
    }

    /**
     * 연결 확인용 테스트 쿼리. onEnable 에서 호출해 성공/실패 콘솔 로그를 찍고, 실패 시 플러그인을 비활성화한다.
     *
     * @return 연결 성공 여부
     */
    public boolean testConnection() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 시작 시 스키마 자동 생성/보강.
     *
     * <p>여러 백엔드가 동시에 재시작하면 같은 DDL 이 동시에 실행되어 메타데이터 락 경합·중복 컬럼
     * 경합으로 한 서버만 실패할 수 있다(그 서버는 스스로 비활성화되어 크레딧이 죽는다).
     * 그래서 <b>네임드 락으로 직렬화</b>하고, 컬럼/인덱스 보강은 information_schema 로 존재 여부를
     * 확인한 뒤 필요한 것만 실행한다(MySQL 은 {@code ADD COLUMN IF NOT EXISTS} 를 지원하지 않으므로
     * 이 방식이 MariaDB/MySQL 모두에서 동작한다).
     */
    public void initSchema() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            boolean locked = acquireLock(conn);
            try {
                createAndMigrate(conn);
            } finally {
                if (locked) {
                    releaseLock(conn);
                }
            }
        }
    }

    private boolean acquireLock(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT GET_LOCK('credit_schema_init', 10)")) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (SQLException e) {
            return false; // 락을 못 잡아도 스키마 생성 자체는 시도한다(IF NOT EXISTS 라 대개 무해)
        }
    }

    private void releaseLock(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT RELEASE_LOCK('credit_schema_init')");
        } catch (SQLException ignored) {
            // 커넥션 종료 시 자동 해제
        }
    }

    private void createAndMigrate(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            // 잔액 (단일 진실원)
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS credit_balance (
                      uuid       CHAR(36)  NOT NULL PRIMARY KEY,
                      balance    BIGINT    NOT NULL DEFAULT 0,
                      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            // 원장(감사/복구용 append-only)
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS credit_ledger (
                      id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                      uuid          CHAR(36) NOT NULL,
                      delta         BIGINT   NOT NULL,
                      balance_after BIGINT   NOT NULL,
                      reason        VARCHAR(64) NOT NULL,
                      ref           VARCHAR(64) NULL,
                      created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      INDEX idx_uuid (uuid),
                      INDEX idx_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            // 홈페이지 결제 멱등성(정확히 한 번 지급) + 분쟁 방지 감사 기록.
            // 자동충전 1건당 1행: 지급 전/후 잔액, 지급액, 닉네임, 처리 시각을 지급 트랜잭션 안에서 그대로 보존.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS credit_processed_charge (
                      charge_id      VARCHAR(64) NOT NULL PRIMARY KEY,
                      uuid           CHAR(36)    NOT NULL,
                      nickname       VARCHAR(30) NULL,
                      amount         BIGINT      NOT NULL,
                      balance_before BIGINT      NULL,
                      balance_after  BIGINT      NULL,
                      processed_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      INDEX idx_charge_uuid (uuid),
                      INDEX idx_processed_at (processed_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            // 구버전 스키마에서 올라온 경우 감사 컬럼/인덱스 보강.
            // 존재 여부를 information_schema 로 먼저 확인 → MariaDB/MySQL 모두 호환, 불필요한 DDL 미실행.
            addColumnIfMissing(conn, st, "credit_processed_charge", "nickname",
                    "ALTER TABLE credit_processed_charge ADD COLUMN nickname VARCHAR(30) NULL AFTER uuid");
            addColumnIfMissing(conn, st, "credit_processed_charge", "balance_before",
                    "ALTER TABLE credit_processed_charge ADD COLUMN balance_before BIGINT NULL AFTER amount");
            addColumnIfMissing(conn, st, "credit_processed_charge", "balance_after",
                    "ALTER TABLE credit_processed_charge ADD COLUMN balance_after BIGINT NULL AFTER balance_before");
            addIndexIfMissing(conn, st, "credit_processed_charge", "idx_charge_uuid",
                    "ALTER TABLE credit_processed_charge ADD INDEX idx_charge_uuid (uuid)");
            addIndexIfMissing(conn, st, "credit_processed_charge", "idx_processed_at",
                    "ALTER TABLE credit_processed_charge ADD INDEX idx_processed_at (processed_at)");
            // 닉네임 캐시(UUID↔닉네임, 접속 시 갱신). PlayerPoints 의 username_cache 테이블 패턴:
            // 멀티 백엔드 네트워크에서 "다른 서버로만 접속했던" 유저도 닉네임/UUID 해석이 가능해진다.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS credit_username_cache (
                      uuid       CHAR(36)    NOT NULL PRIMARY KEY,
                      username   VARCHAR(30) NOT NULL,
                      updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      INDEX idx_username (username)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
    }

    private void addColumnIfMissing(Connection conn, Statement st, String table, String column, String ddl)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return; // 이미 존재
                }
            }
        }
        try {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            // 다른 서버가 동시에 추가한 경우(중복 컬럼) 무시 — 그 외에는 전파
            if (!isDuplicateObject(e)) {
                throw e;
            }
        }
    }

    private void addIndexIfMissing(Connection conn, Statement st, String table, String index, String ddl)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        }
        try {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            if (!isDuplicateObject(e)) {
                throw e;
            }
        }
    }

    /** 1060 = Duplicate column, 1061 = Duplicate key name, 1050 = Table exists (동시 DDL 경합). */
    private static boolean isDuplicateObject(SQLException e) {
        int c = e.getErrorCode();
        return c == 1060 || c == 1061 || c == 1050;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
