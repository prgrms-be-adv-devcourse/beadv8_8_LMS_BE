package com.hapbang.coupon.domain;

/** 쿠폰 유형. 할인 비용을 누가 부담하는지의 기준이다 */
public enum CouponType {
    /** 판매자 쿠폰. 판매 대금 감소로 판매자가 부담한다. 판매자가 신청하고 관리자가 심사한다 */
    SELLER,
    /** 플랫폼 쿠폰. 프로모션 예산으로 플랫폼이 부담한다. 관리자만 발행한다 */
    PLATFORM
}
