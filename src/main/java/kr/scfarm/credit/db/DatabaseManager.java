package kr.scfarm.credit.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
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
        hikari.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + name);
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

    /** 시작 시 스키마 자동 생성(CREATE TABLE IF NOT EXISTS). */
    public void initSchema() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
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
            // 홈페이지 결제 멱등성(정확히 한 번 지급)
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS credit_processed_charge (
                      charge_id    VARCHAR(64) NOT NULL PRIMARY KEY,
                      uuid         CHAR(36)    NOT NULL,
                      amount       BIGINT      NOT NULL,
                      processed_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
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

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
