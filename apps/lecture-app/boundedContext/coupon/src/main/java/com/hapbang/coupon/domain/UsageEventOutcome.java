package com.hapbang.coupon.domain;

/**
 * 주문 결제·취소 완료 이벤트를 사용 시도 하나에 반영한 결과({@link CouponUsage#applyPaid}·{@link CouponUsage#applyCancelCompleted}).
 */
public enum UsageEventOutcome {
    /** 사용 시도 상태를 바꿨다. 호출자는 발급 쿠폰을 같은 조건(상태·generation)의 조건부 갱신으로 바꾼다 */
    APPLIED,
    /**
     * 사실(이벤트 ID·시각)만 남기고 상태는 그대로 뒀다. 취소·해제가 먼저 끝난 시도에 늦게 온 결제 완료, 해제된 시도의 취소 완료,
     * 발급 쿠폰이 이미 다른 generation(다른 사용 시도)으로 넘어간 경우다
     */
    FACT_ONLY,
    /** 이미 같은 결과다(결제 완료가 이미 PAID, 취소 완료가 이미 CANCELED). 아무것도 바꾸지 않았다 */
    ALREADY_APPLIED
}
