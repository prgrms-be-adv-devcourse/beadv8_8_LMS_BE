package com.hapbang.coupon.domain;

/** 쿠폰 적용 대상 */
public enum CouponTargetType {
    /** 특정 강의 하나 */
    LECTURE,
    /** 모든 강의 중 하나(주문 전체 쿠폰이 아니다) */
    ANY_LECTURE
}
