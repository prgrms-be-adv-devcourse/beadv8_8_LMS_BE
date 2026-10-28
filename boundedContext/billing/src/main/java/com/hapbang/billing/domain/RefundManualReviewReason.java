package com.hapbang.billing.domain;

// 개별 환불(PG 취소 건)을 운영자 확인(MANUAL_REVIEW_REQUIRED)으로 넘긴 원인이다 (#260). 관리자 확인 대상 조회·분류에 쓴다.
// 어느 원인이든 그 건의 충전 HP는 보류로 남고 자동으로 반환·확정 차감하지 않는다.
public enum RefundManualReviewReason {
    /** PG가 취소를 확정적으로 거절했다(취소 기간 경과·부분 취소 불가 결제수단·취소 불가 결제 등). PG 환급은 불가능하지만 환불 청구는 남는다 */
    PG_REFUND_NOT_POSSIBLE,
    /** 이미 취소됨·취소 가능 금액 부족 등으로 거절됐는데 취소 이력에 우리 취소가 없다(PG와 기록 불일치) */
    NO_MATCHING_CANCEL,
    /** 결제 조회가 404다(승인된 결제가 PG에 없음) */
    PAYMENT_NOT_FOUND,
    /** 같은 표식의 PG 취소가 여러 건이다(중복 취소) */
    DUPLICATE_CANCEL,
    /** PG가 취소한 금액이 요청 금액과 다르다 */
    CANCEL_AMOUNT_MISMATCH,
    /** 우리 취소가 DONE이 아니거나 거래 키가 없다 */
    CANCEL_NOT_CONFIRMED,
    /** 결제키를 복호화할 수 없어 PG 결과를 확인할 수 없다 */
    PAYMENT_KEY_UNREADABLE,
    /** UNKNOWN이 된 뒤 24시간이 지났다(자동 복구 시간 한도) */
    UNKNOWN_TIME_LIMIT,
    /** UNKNOWN 결과 재확인이 12회에 도달했다(자동 복구 횟수 한도) */
    UNKNOWN_CHECK_LIMIT,
    /** 멱등키 안전 기간이 지나 같은 키로 다시 요청할 수 없고, 조회로도 결과를 확정하지 못했다 */
    IDEMPOTENCY_KEY_EXPIRED
}
