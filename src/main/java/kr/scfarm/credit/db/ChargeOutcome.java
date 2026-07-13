package kr.scfarm.credit.db;

/**
 * 홈페이지 결제 1건 처리 결과 + 감사 정보.
 *
 * <p>{@code balanceBefore}/{@code balanceAfter} 는 {@link ChargeResult#PAID} 일 때만 유효하다
 * (지급 트랜잭션 안에서 확정된 값 — 분쟁 방지 기록용). 그 외에는 -1.
 */
public record ChargeOutcome(ChargeResult status, long balanceBefore, long balanceAfter) {

    public static ChargeOutcome paid(long before, long after) {
        return new ChargeOutcome(ChargeResult.PAID, before, after);
    }

    public static ChargeOutcome alreadyProcessed() {
        return new ChargeOutcome(ChargeResult.ALREADY_PROCESSED, -1, -1);
    }
}
