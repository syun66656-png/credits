package kr.scfarm.credit.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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
    // set() 전용 잠금 읽기. 일반 SELECT 로 읽으면 읽은 뒤 UPSERT 하기 전에 다른 트랜잭션이 커밋한
    // 증감이 통째로 덮어써지고(lost update), 원장에는 거짓 delta 가 남아 유실을 추적조차 못 하게 된다.
    private static final String SQL_SELECT_BALANCE_LOCK =
            "SELECT balance FROM credit_balance WHERE uuid = ? FOR UPDATE";
    // 잔액 행 보장(없으면 0으로 생성). 없는 PK 에 FOR UPDATE 를 걸면 갭 락 데드락이 나므로 먼저 만든다.
    private static final String SQL_ENSURE_ROW =
            "INSERT INTO credit_balance (uuid, balance) VALUES (?, 0) " +
            "ON DUPLICATE KEY UPDATE uuid = uuid";
    private static final String SQL_LEDGER_INSERT =
            "INSERT INTO credit_ledger (uuid, delta, balance_after, reason, ref) VALUES (?, ?, ?, ?, ?)";
    private static final String SQL_PROCESSED_INSERT =
            "INSERT INTO credit_processed_charge (charge_id, uuid, nickname, amount) VALUES (?, ?, ?, ?)";
    // 지급 트랜잭션 안에서 확정된 전/후 잔액을 같은 행에 남긴다(분쟁 방지 감사 기록)
    private static final String SQL_PROCESSED_AUDIT =
            "UPDATE credit_processed_charge SET balance_before = ?, balance_after = ? WHERE charge_id = ?";
    // 닉네임 캐시(PlayerPoints username_cache 패턴). 닉변으로 같은 이름이 여러 UUID 에 남을 수 있으므로
    // 이름→UUID 는 가장 최근 기록을 택한다(PlayerPoints 는 임의 1건이라 닉변 시 오지급 여지가 있었음 — 개선).
    private static final String SQL_USERNAME_UPSERT =
            "INSERT INTO credit_username_cache (uuid, username) VALUES (?, ?) " +
            "ON DUPLICATE KEY UPDATE username = VALUES(username)";
    private static final String SQL_USERNAME_BY_UUID =
            "SELECT username FROM credit_username_cache WHERE uuid = ?";
    private static final String SQL_UUID_BY_NAME =
            "SELECT uuid FROM credit_username_cache WHERE LOWER(username) = LOWER(?) " +
            "ORDER BY updated_at DESC LIMIT 1";

    /** 데드락/락 타임아웃 재시도 횟수. */
    private static final int MAX_RETRY = 3;

    /**
     * 1회 연산 금액 상한(1조). 오타(0 하나 더)·홈페이지 버그·변조로 천문학적 금액이 들어오는 것을 막는다.
     * BIGINT 상한(9.2e18) 대비 충분히 낮아 잔액 오버플로(에러 1690 으로 계정이 영구 고장)도 예방한다.
     */
    public static final long MAX_AMOUNT = 1_000_000_000_000L;
    /** credit_processed_charge.charge_id 컬럼 길이. 초과 시 조용한 truncate/충돌 대신 명시적 거부. */
    private static final int CHARGE_ID_MAX = 64;
    /** credit_processed_charge.nickname 컬럼 길이(참고용 필드가 지급을 막지 못하게 방어적으로 자른다). */
    private static final int NICKNAME_MAX = 30;
    /** credit_ledger.reason / ref 컬럼 길이. */
    private static final int REASON_MAX = 64;

    /** 금액이 유효한 범위(1 ~ MAX_AMOUNT)인지. */
    private static boolean validAmount(long amount) {
        return amount > 0 && amount <= MAX_AMOUNT;
    }

    private static String clamp(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

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
        return withRetry(() -> give0(uuid, amount, reason, ref));
    }

    private boolean give0(UUID uuid, long amount, String reason, String ref) throws SQLException {
        if (!validAmount(amount)) {
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

            commitOnce(conn);
            return true;
        } catch (Throwable t) {
            // SQLException 뿐 아니라 RuntimeException/Error 도 반드시 롤백한다.
            // (놓치면 커넥션 반납 시 반쪽 트랜잭션이 남아 돈이 생성/소멸될 수 있다)
            rollbackQuietly(conn);
            throw t;
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 차감: 원자적 조건부 UPDATE. <b>잔액 부족(영향 행 0)이면 아무것도 바꾸지 않고 false.</b>
     * 성공 시 같은 트랜잭션에서 원장 기록. 절대 음수 불가. amount<=0 은 실패.
     */
    public boolean take(UUID uuid, long amount, String reason, String ref) throws SQLException {
        return withRetry(() -> take0(uuid, amount, reason, ref));
    }

    private boolean take0(UUID uuid, long amount, String reason, String ref) throws SQLException {
        if (!validAmount(amount)) {
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

            commitOnce(conn);
            return true;
        } catch (Throwable t) {
            // SQLException 뿐 아니라 RuntimeException/Error 도 반드시 롤백한다.
            // (놓치면 커넥션 반납 시 반쪽 트랜잭션이 남아 돈이 생성/소멸될 수 있다)
            rollbackQuietly(conn);
            throw t;
        } finally {
            closeQuietly(conn);
        }
    }

    /** 설정: 잔액을 amount 로 고정. 증감분(delta)을 원장에 기록. amount<0 은 실패. */
    public boolean set(UUID uuid, long amount, String reason) throws SQLException {
        return withRetry(() -> set0(uuid, amount, reason));
    }

    private boolean set0(UUID uuid, long amount, String reason) throws SQLException {
        if (amount < 0 || amount > MAX_AMOUNT) {
            return false;
        }
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // 행이 없으면 먼저 0 으로 만든다. 없는 PK 에 FOR UPDATE 를 걸면 갭 락이 잡혀
            // 서로 다른 신규 UUID 끼리도 데드락이 나기 때문에, 실재하는 행을 잠그도록 보장한다.
            ensureRow(conn, uuid);
            long before = readBalanceForUpdate(conn, uuid); // 행 잠금 후 읽기 → lost update 방지
            try (PreparedStatement up = conn.prepareStatement(SQL_SET_UPSERT)) {
                up.setString(1, uuid.toString());
                up.setLong(2, amount);
                up.executeUpdate();
            }
            insertLedger(conn, uuid, amount - before, amount, reason, null);

            commitOnce(conn);
            return true;
        } catch (Throwable t) {
            // SQLException 뿐 아니라 RuntimeException/Error 도 반드시 롤백한다.
            // (놓치면 커넥션 반납 시 반쪽 트랜잭션이 남아 돈이 생성/소멸될 수 있다)
            rollbackQuietly(conn);
            throw t;
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 이체: 출금(조건부 차감) + 입금(UPSERT) 을 단일 트랜잭션으로. 잔액 부족 시 전체 롤백 후 false.
     *
     * <p>행 잠금은 항상 <b>UUID 사전순</b>으로 획득한다 — A→B 와 B→A 이체가 동시에 일어나면 서로 반대
     * 순서로 행을 잠가 데드락이 나는 고전적 문제(PlayerPoints 의 "Lock on point modifications to
     * prevent duplications" 커밋이 다룬 동시성 버그 계열)를 잠금 순서 고정으로 원천 차단한다.
     * 입금을 먼저 실행한 경우에도 출금 실패 시 전체 롤백되므로 원자성은 유지된다.
     */
    public boolean pay(UUID from, UUID to, long amount, String reason) throws SQLException {
        return withRetry(() -> pay0(from, to, amount, reason));
    }

    private boolean pay0(UUID from, UUID to, long amount, String reason) throws SQLException {
        if (!validAmount(amount) || from.equals(to)) {
            return false;
        }
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            boolean fromFirst = from.toString().compareTo(to.toString()) < 0;
            if (fromFirst) {
                if (!takeRow(conn, from, amount)) {
                    conn.rollback();
                    return false;
                }
                giveRow(conn, to, amount);
            } else {
                giveRow(conn, to, amount);
                if (!takeRow(conn, from, amount)) {
                    conn.rollback(); // 입금까지 함께 되돌린다
                    return false;
                }
            }

            long fromAfter = readBalance(conn, from);
            long toAfter = readBalance(conn, to);
            insertLedger(conn, from, -amount, fromAfter, reason, to.toString());
            insertLedger(conn, to, amount, toAfter, reason, from.toString());

            commitOnce(conn);
            return true;
        } catch (Throwable t) {
            // SQLException 뿐 아니라 RuntimeException/Error 도 반드시 롤백한다.
            // (놓치면 커넥션 반납 시 반쪽 트랜잭션이 남아 돈이 생성/소멸될 수 있다)
            rollbackQuietly(conn);
            throw t;
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 홈페이지 결제 1건을 "정확히 한 번" 지급 + 분쟁 방지 감사 기록. 단일 트랜잭션:
     * <pre>
     *   INSERT processed_charge(charge_id, uuid, nickname, amount)  -- PK 충돌 = 이미 처리됨 → ALREADY_PROCESSED
     *   SELECT balance FOR UPDATE                                   -- ① 지급 전 잔액 실측(행 잠금)
     *   UPSERT balance += amount                                    -- ② 지급
     *   SELECT balance                                              -- ③ 지급 후 잔액 실측
     *   검증: ③ - ① == amount  아니면 전체 롤백(BALANCE_MISMATCH)      -- ④ 대조
     *   UPDATE processed_charge SET balance_before=①, balance_after=③
     *   INSERT ledger(delta=+amount, reason=HOMEPAGE_CHARGE, ref=charge_id)
     * </pre>
     *
     * <p><b>"지급 완료" 판정 기준</b>: 지급 전 잔액과 지급 후 잔액을 <b>각각 실측</b>해
     * {@code 지급후 - 지급전 == 지급수량} 이 성립할 때에만 커밋하고 {@link ChargeResult#PAID} 를 돌려준다.
     * 이 등식이 깨지면 커밋하지 않고 되돌려 {@link ChargeResult#BALANCE_MISMATCH} 를 돌려주며,
     * 호출측(브릿지)은 완료 보고를 하지 않는다. charge_id PK 충돌로 재지급은 원천 차단된다.
     * 오류 시 전체 롤백(감사 기록도 지급과 함께만 남는다 — 반쪽 기록 불가).
     */
    public ChargeOutcome processCharge(String chargeId, UUID uuid, String nickname, long amount) throws SQLException {
        return withRetry(() -> processCharge0(chargeId, uuid, nickname, amount));
    }

    private ChargeOutcome processCharge0(String chargeId, UUID uuid, String nickname, long amount) throws SQLException {
        // 부적합 데이터는 지급하지 않고 REJECTED — 완료 보고를 하지 않아 홈페이지에 '미지급'으로 남는다.
        // (지급하지 않은 건을 완료로 보고하면 유저가 결제하고 아무것도 못 받는 조용한 손실이 된다)
        if (!validAmount(amount)) {
            return ChargeOutcome.rejected();
        }
        if (chargeId.length() > CHARGE_ID_MAX) {
            // 컬럼 길이 초과 → 조용한 truncate 로 다른 건과 PK 충돌해 '이미 지급'으로 오판할 수 있다.
            return ChargeOutcome.rejected();
        }
        // 닉네임은 참고용 필드다. 길이 초과로 지급 트랜잭션이 실패해 결제가 영구히 막히지 않도록 잘라서 넣는다.
        String safeNickname = clamp(nickname, NICKNAME_MAX);
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(SQL_PROCESSED_INSERT)) {
                ps.setString(1, chargeId);
                ps.setString(2, uuid.toString());
                setNullableString(ps, 3, safeNickname);
                ps.setLong(4, amount);
                ps.executeUpdate();
            } catch (SQLException dup) {
                if (isDuplicateKey(dup)) {
                    conn.rollback(); // 이미 지급된 건 → 재지급하지 않음
                    return ChargeOutcome.alreadyProcessed();
                }
                throw dup;
            }

            // ── 지급 전 잔액 실측 ──────────────────────────────────────────
            // 행을 만들고(없으면 0) X-잠금한 뒤 읽는다. 역산(after - amount)이 아니라 실측해야
            // 아래 검증이 의미를 갖는다(역산하면 등식이 항상 참이라 검증 자체가 성립하지 않는다).
            ensureRow(conn, uuid);
            long before = readBalanceForUpdate(conn, uuid);

            // ── 지급 ──────────────────────────────────────────────────────
            try (PreparedStatement up = conn.prepareStatement(SQL_GIVE_UPSERT)) {
                up.setString(1, uuid.toString());
                up.setLong(2, amount);
                up.executeUpdate();
            }

            // ── 지급 후 잔액 실측 + 대조 검증 ────────────────────────────────
            // 위에서 잠근 행을 같은 트랜잭션에서 읽으므로 자기 UPSERT 결과가 보인다.
            // ※ 이 SELECT 는 단순 감사용이 아니라 "지급 완료" 판정 게이트다. JDBC URL 을
            //   jdbc:mariadb:replication:// 로 바꾸거나 읽기/쓰기 분리 프록시를 넣으면 이 읽기가
            //   레플리카로 라우팅돼 모든 지급이 검증 실패할 수 있다(단일 호스트 URL 을 유지할 것).
            long after = readBalance(conn, uuid);
            if (after - before != amount) {
                // 지급이 온전히 반영되지 않았다(트리거/외부 수정/클램프 등).
                // 커밋하지 않고 전체 롤백 → 지급도, 완료 보고도 하지 않는다.
                conn.rollback();
                return ChargeOutcome.mismatch(before, after);
            }

            // 검증을 통과한 값만 감사 기록으로 남긴다(실측 전 잔액 + 실측 후 잔액).
            try (PreparedStatement ps = conn.prepareStatement(SQL_PROCESSED_AUDIT)) {
                ps.setLong(1, before);
                ps.setLong(2, after);
                ps.setString(3, chargeId);
                ps.executeUpdate();
            }
            insertLedger(conn, uuid, amount, after, "HOMEPAGE_CHARGE", chargeId);

            commitOnce(conn);
            return ChargeOutcome.paid(before, after);
        } catch (Throwable t) {
            // SQLException 뿐 아니라 RuntimeException/Error 도 반드시 롤백한다.
            // (놓치면 커넥션 반납 시 반쪽 트랜잭션이 남아 돈이 생성/소멸될 수 있다)
            rollbackQuietly(conn);
            throw t;
        } finally {
            closeQuietly(conn);
        }
    }

    // ── 닉네임 캐시 (PlayerPoints username_cache 패턴) ──────────────────────

    /** 접속/조회로 확인된 닉네임을 DB 캐시에 반영(단문 UPSERT, 자체 원자적). */
    public void upsertUsername(UUID uuid, String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return;
        }
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_USERNAME_UPSERT)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, clamp(username, NICKNAME_MAX));
            ps.executeUpdate();
        }
    }

    /** DB 닉네임 캐시에서 UUID→닉네임 조회. 없으면 null. */
    public String lookupUsername(UUID uuid) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_USERNAME_BY_UUID)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** DB 닉네임 캐시에서 닉네임→UUID 조회(대소문자 무시, 최근 갱신 우선). 없으면 null. */
    public UUID lookupUuidByName(String username) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UUID_BY_NAME)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        }
    }

    // ── 배치 조회 (온라인 유저 캐시 주기 리프레시용) ─────────────────────────

    /**
     * 여러 UUID 의 잔액을 한 번의 쿼리로 조회한다. 행이 없는 UUID 는 0 으로 채워 반환한다.
     * (PlayerPoints 의 pointsCache 주기 갱신에 해당 — 우리 placeholder 캐시의 교차서버 스테일 해소용)
     */
    public Map<UUID, Long> getBalances(Collection<UUID> uuids) throws SQLException {
        Map<UUID, Long> out = new HashMap<>();
        if (uuids.isEmpty()) {
            return out;
        }
        for (UUID u : uuids) {
            out.put(u, 0L);
        }
        StringBuilder sql = new StringBuilder("SELECT uuid, balance FROM credit_balance WHERE uuid IN (");
        sql.append("?,".repeat(uuids.size()));
        sql.setCharAt(sql.length() - 1, ')');
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            for (UUID u : uuids) {
                ps.setString(i++, u.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                }
            }
        }
        return out;
    }

    // ── 내부 헬퍼 ───────────────────────────────────────────────────────────

    /** 조건부 차감 1행. 잔액 부족(영향 행 0)이면 false. */
    private boolean takeRow(Connection conn, UUID uuid, long amount) throws SQLException {
        try (PreparedStatement up = conn.prepareStatement(SQL_TAKE_CONDITIONAL)) {
            up.setLong(1, amount);
            up.setString(2, uuid.toString());
            up.setLong(3, amount);
            return up.executeUpdate() > 0;
        }
    }

    /** 지급 UPSERT 1행. */
    private void giveRow(Connection conn, UUID uuid, long amount) throws SQLException {
        try (PreparedStatement up = conn.prepareStatement(SQL_GIVE_UPSERT)) {
            up.setString(1, uuid.toString());
            up.setLong(2, amount);
            up.executeUpdate();
        }
    }

    private long readBalance(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BALANCE)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** 잔액 행이 없으면 0 으로 생성(이미 있으면 아무 일도 하지 않음). FOR UPDATE 갭 락 회피용. */
    private void ensureRow(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_ENSURE_ROW)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** 행을 X-잠금한 뒤 잔액을 읽는다(set 처럼 "읽고 나서 덮어쓰는" 연산 전용). */
    private long readBalanceForUpdate(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BALANCE_LOCK)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            ps.setNull(index, java.sql.Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private void insertLedger(Connection conn, UUID uuid, long delta, long balanceAfter,
                              String reason, String ref) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_LEDGER_INSERT)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, delta);
            ps.setLong(3, balanceAfter);
            // 외부 플러그인이 긴 reason/ref 를 넘겨도 컬럼 초과로 지급 트랜잭션이 실패하지 않도록 방어적으로 자른다.
            ps.setString(4, clamp(reason == null ? "UNKNOWN" : reason, REASON_MAX));
            setNullableString(ps, 5, clamp(ref, REASON_MAX));
            ps.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface SqlOp<T> {
        T run() throws SQLException;
    }

    /**
     * commit() 단계에서 실패해 <b>실제 반영 여부를 알 수 없는</b> 상태. 재시도 금지 표식.
     * (재시도하면 이미 커밋된 연산이 한 번 더 적용될 수 있다)
     */
    private static final class CommitAmbiguousException extends SQLException {
        private static final long serialVersionUID = 1L;

        CommitAmbiguousException(SQLException cause) {
            super("커밋 결과 불명(재시도하지 않음): " + cause.getMessage(), cause.getSQLState(), cause.getErrorCode(), cause);
        }
    }

    /** commit() 호출을 감싸 실패 시 재시도 불가로 표시한다. */
    private static void commitOnce(Connection conn) throws SQLException {
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new CommitAmbiguousException(e);
        }
    }

    /**
     * 데드락(1213/40001)·락 대기 타임아웃(1205) 시 짧은 백오프 후 재시도. InnoDB 는 데드락 감지 시
     * 한쪽 트랜잭션을 롤백하고 예외를 던지는데, 우리 트랜잭션은 전부 자기완결적이라 재실행이 안전하다.
     * (PlayerPoints 가 메모리 잠금으로 풀었던 동시 변경 문제의 DB 레벨 대응)
     *
     * <p><b>단, commit() 자체가 던진 오류는 재시도하지 않는다</b>({@link CommitAmbiguousException}).
     * 커밋이 서버에서 성공했는데 응답만 유실된 경우(프록시 페일오버, 커넥션 강제 종료, Galera 인증 충돌)
     * 재실행하면 같은 금액이 두 번 반영된다. 애매하면 재시도 대신 실패로 보고하는 쪽이 안전하다.
     */
    private <T> T withRetry(SqlOp<T> op) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return op.run();
            } catch (SQLException e) {
                if (!isTransientConflict(e) || attempt == MAX_RETRY) {
                    throw e;
                }
                last = e;
                try {
                    Thread.sleep(30L * attempt); // 비동기 스레드에서만 호출되므로 짧은 sleep 허용
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last; // 도달 불가(위에서 throw)지만 컴파일러용
    }

    private static boolean isTransientConflict(SQLException e) {
        if (e instanceof CommitAmbiguousException) {
            return false;                           // 커밋 결과 불명 → 재시도 시 중복 반영 위험
        }
        return "40001".equals(e.getSQLState())      // 직렬화 실패(데드락 표준 상태)
                || e.getErrorCode() == 1213         // MariaDB deadlock
                || e.getErrorCode() == 1205;        // MariaDB lock wait timeout
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

    /**
     * 커넥션 반납. <b>절대 여기서 setAutoCommit(true) 를 호출하지 않는다.</b>
     * JDBC 규약상 setAutoCommit(true) 는 열린 트랜잭션을 <b>커밋</b>해 버리고, HikariCP 가 반납 시
     * 수행하는 자동 롤백(dirty 트랜잭션 보호)까지 무력화한다 → 롤백을 놓친 경로에서 반쪽 트랜잭션이
     * 커밋되어 돈이 생성/소멸될 수 있다. autoCommit 은 Hikari 가 풀 기본값으로 알아서 복원한다.
     */
    private static void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // ignore
            }
        }
    }
}
