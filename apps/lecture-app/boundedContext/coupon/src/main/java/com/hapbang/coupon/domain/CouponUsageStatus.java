package com.hapbang.coupon.domain;

/**
 * 쿠폰 사용 시도 상태. 예약으로 RESERVED가 되고, 해제하면 RELEASED로 끝난다. PAID·CANCELED는 결제·취소 연동이 쓴다.
 */
public enum CouponUsageStatus {
    RESERVED,
    RELEASED,
    PAID,
    CANCELED
}
