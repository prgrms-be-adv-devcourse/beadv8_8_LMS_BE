package com.hapbang.coupon.domain;

/**
 * 쿠폰 사용 시도 상태. 예약으로 RESERVED가 되고, 해제하면 RELEASED로 끝난다. 주문 결제 완료 이벤트로 PAID, 취소·반환 완료 이벤트로
 * CANCELED가 된다(RESERVED·PAID → CANCELED). RELEASED·CANCELED는 끝 상태다.
 */
public enum CouponUsageStatus {
    RESERVED,
    RELEASED,
    PAID,
    CANCELED
}
