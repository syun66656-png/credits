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
    FAILED,

    /**
     * 결제 데이터 자체가 부적합해 지급할 수 없음(금액 ≤0 / 상한 초과 / charge_id 길이 초과 등).
     *
     * <p><b>완료 보고를 하지 않는다.</b> 지급하지 않은 건을 "처리완료"로 보고하면 홈페이지가 지급된 것으로
     * 확정해 버려 결제한 유저가 아무것도 못 받고 추적도 불가능해진다(조용한 금전 손실). 대신 ERROR 로
     * 크게 남겨 관리자가 수동 정산하게 한다 — 홈페이지에는 계속 '미지급'으로 남는 편이 안전하다.
     */
    REJECTED
}
