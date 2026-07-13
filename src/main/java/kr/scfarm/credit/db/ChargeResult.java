package kr.scfarm.credit.db;

/**
 * 홈페이지 결제 1건 처리 결과.
 *
 * <p>{@link #PAID} 와 {@link #ALREADY_PROCESSED} 는 모두 "성공적으로 수렴된 상태"로,
 * 브릿지는 두 경우 모두 홈페이지에 완료 보고를 보낸다. {@link #FAILED} 만 보고를 보류하고 다음 폴링에 재시도한다.
 */
public enum ChargeResult {
    /** 이번에 지급됨(잔액 증가 + 원장 기록 + processed 기록 커밋). */
    PAID,
    /** 이미 처리된 charge_id (PK 충돌) → 재지급 없음. 보고만 재시도. */
    ALREADY_PROCESSED,
    /** DB 오류 등으로 지급 실패 → processed 기록/보고 하지 않음. 다음 폴링에 재시도. */
    FAILED
}
