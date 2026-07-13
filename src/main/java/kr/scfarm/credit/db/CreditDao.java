package kr.scfarm.credit.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.UUID;

/**
 * 크레딧 데이터 접근 계층. <b>모든 쓰기는 트랜잭션</b>(autoCommit=false + commit/rollback)이며,
 * 잔액은 절대 음수가 될 수 없고 중복 지급이 발생하지 않도록 원자적 SQL 로 구성한다.
 *
 * <p>여기의 모든 메서드는 <b>동기</b>이며, 반드시 비동기 스레드(크레딧 실행자)에서 호출된다.
 * UUID 기반이라 오프라인/미접속 유저도 안전하게 처리된다(Player 객체 불필요).
 */
public final class CreditDao {

    // MariaDB: ON DUPLICATE KEY UPDATE 에서 VALUES() 로 삽입 예정값을 참조(증분/설정 공용).
    private static final String SQL_GIVE_UPSERT =
            "INSERT INTO credit_balance (uuid, balance) VALUES (?, ?) " +
            "ON DUPLICATE KEY UPDATE balance = balance + VALUES(balance)";
    private static final String SQL_SET_UPSERT =
            "INSERT INTO credit_balance (uuid, balance) VALUES (?, ?) " +
            "ON DUPLICATE KEY UPDATE balance = VALUES(balance)";
    private static final String SQL_TAKE_CONDITIONAL =
            "UPDATE credit_balance SET balance = balance - ? WHERE uuid = ? AND balance >= ?";
    private static final String SQL_SELECT_BALANCE =
            "SELECT balance FROM credit_balance WHERE uuid = ?";
    private static final String SQL_LEDGER_INSERT =
            "INSERT INTO credit_ledger (uuid, delta, balance_after, reason, ref) VALUES (?, ?, ?, ?, ?)";
    private static final String SQL_PROCESSED_INSERT =
            "INSERT INTO credit_processed_charge (charge_id, uuid, amount) VALUES (?, ?, ?)";

    private final DatabaseManager db;

    public CreditDao(DatabaseManager db) {
        this.db = db;
    }

    /** 잔액 조회(계정 없으면 0). 읽기 전용이라 트랜잭션 불필요. */
    public long getBalance(UUID uuid) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BALANCE)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** 지급: 원자적 UPSERT + 같은 트랜잭션에서 원장 기록. amount<=0 은 실패. */
    public boolean give(UUID uuid, long amount, String reason, String ref) throws SQLException {
        if (amount <= 0) {
            return false;
        }
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement up = conn.prepareStatement(SQL_GIVE_UPSERT)) {
                up.setString(1, uuid.toString());
                up.setLong(2, amount);
                up.executeUpdate();
            }
            long after = readBalance(conn, uuid);
            insertLedger(conn, uuid, amount, after, reason, ref);

            conn.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 차감: 원자적 조건부 UPDATE. <b>잔액 부족(영향 행 0)이면 아무것도 바꾸지 않고 false.</b>
     * 성공 시 같은 트랜잭션에서 원장 기록. 절대 음수 불가. amount<=0 은 실패.
     */
    public boolean take(UUID uuid, long amount, String reason, String ref) throws SQLException {
        if (amount <= 0) {
            return false;
        }
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            int affected;
            try (PreparedStatement up = conn.prepareStatement(SQL_TAKE_CONDITIONAL)) {
                up.setLong(1, amount);
                up.setString(2, uuid.toString());
                up.setLong(3, amount);
                affected = up.executeUpdate();
            }
            if (affected == 0) {
                conn.rollback(); // 잔액 부족 또는 계정 없음
                return false;
            }
            long after = readBalance(conn, uuid);
            insertLedger(conn, uuid, -amount, after, reason, ref);

            conn.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        } finally {
            closeQuietly(conn);
        }
    }

    /** 설정: 잔액을 amount 로 고정. 증감분(delta)을 원장에 기록. amount<0 은 실패. */
    public boolean set(UUID uuid, long amount, String reason) throws SQLException {
        if (amount < 0) {
            return false;
        }
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            long before = readBalance(conn, uuid);
            try (PreparedStatement up = conn.prepareStatement(SQL_SET_UPSERT)) {
                up.setString(1, uuid.toString());
                up.setLong(2, amount);
                up.executeUpdate();
            }
            insertLedger(conn, uuid, amount - before, amount, reason, null);

            conn.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        } finally {
            closeQuietly(conn);
        }
    }

    /** 이체: 출금(조건부 차감) → 입금(UPSERT) 을 단일 트랜잭션으로. 잔액 부족 시 전체 롤백 후 false. */
    public boolean pay(UUID from, UUID to, long amount, String reason) throws SQLException {
        if (amount <= 0 || from.equals(to)) {
            return false;
        }
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            int affected;
            try (PreparedStatement up = conn.prepareStatement(SQL_TAKE_CONDITIONAL)) {
                up.setLong(1, amount);
                up.setString(2, from.toString());
                up.setLong(3, amount);
                affected = up.executeUpdate();
            }
            if (affected == 0) {
                conn.rollback();
                return false;
            }
            try (PreparedStatement up = conn.prepareStatement(SQL_GIVE_UPSERT)) {
                up.setString(1, to.toString());
                up.setLong(2, amount);
                up.executeUpdate();
            }
            long fromAfter = readBalance(conn, from);
            long toAfter = readBalance(conn, to);
            insertLedger(conn, from, -amount, fromAfter, reason, to.toString());
            insertLedger(conn, to, amount, toAfter, reason, from.toString());

            conn.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 홈페이지 결제 1건을 "정확히 한 번" 지급. 단일 트랜잭션:
     * <pre>
     *   INSERT processed_charge(charge_id,...)   -- PK 충돌 = 이미 처리됨 → ALREADY_PROCESSED
     *   UPSERT balance += amount
     *   INSERT ledger(delta=+amount, reason=HOMEPAGE_CHARGE, ref=charge_id)
     * </pre>
     * charge_id PK 충돌로 재지급이 원천 차단된다. 오류 시 롤백 후 {@link ChargeResult#FAILED}.
     */
    public ChargeResult processCharge(String chargeId, UUID uuid, long amount) throws SQLException {
        if (amount <= 0) {
            // 비정상 금액은 지급하지 않지만, 무한 재처리를 막기 위해 이미처리로 간주(processed 만 기록).
            return markInvalidCharge(chargeId, uuid, amount);
        }
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(SQL_PROCESSED_INSERT)) {
                ps.setString(1, chargeId);
                ps.setString(2, uuid.toString());
                ps.setLong(3, amount);
                ps.executeUpdate();
            } catch (SQLException dup) {
                if (isDuplicateKey(dup)) {
                    conn.rollback(); // 이미 지급된 건 → 재지급하지 않음
                    return ChargeResult.ALREADY_PROCESSED;
                }
                throw dup;
            }

            try (PreparedStatement up = conn.prepareStatement(SQL_GIVE_UPSERT)) {
                up.setString(1, uuid.toString());
                up.setLong(2, amount);
                up.executeUpdate();
            }
            long after = readBalance(conn, uuid);
            insertLedger(conn, uuid, amount, after, "HOMEPAGE_CHARGE", chargeId);

            conn.commit();
            return ChargeResult.PAID;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw e;
        } finally {
            closeQuietly(conn);
        }
    }

    // 금액이 비정상인 결제 건: 지급 없이 processed 만 기록해 무한 재폴링을 끊는다(이미 기록돼 있으면 그대로 수렴).
    private ChargeResult markInvalidCharge(String chargeId, UUID uuid, long amount) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_PROCESSED_INSERT)) {
            ps.setString(1, chargeId);
            ps.setString(2, uuid.toString());
            ps.setLong(3, Math.max(0, amount));
            ps.executeUpdate();
        } catch (SQLException dup) {
            if (!isDuplicateKey(dup)) {
                throw dup;
            }
        }
        return ChargeResult.ALREADY_PROCESSED;
    }

    private long readBalance(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BALANCE)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private void insertLedger(Connection conn, UUID uuid, long delta, long balanceAfter,
                              String reason, String ref) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_LEDGER_INSERT)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, delta);
            ps.setLong(3, balanceAfter);
            ps.setString(4, reason == null ? "UNKNOWN" : reason);
            if (ref == null) {
                ps.setNull(5, java.sql.Types.VARCHAR);
            } else {
                ps.setString(5, ref);
            }
            ps.executeUpdate();
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        return state != null && state.startsWith("23"); // 무결성 제약 위반
    }

    private static void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 롤백 실패는 무시(연결 종료 시 자동 롤백)
            }
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // ignore
            }
            try {
                conn.close();
            } catch (SQLException ignored) {
                // ignore
            }
        }
    }
}
