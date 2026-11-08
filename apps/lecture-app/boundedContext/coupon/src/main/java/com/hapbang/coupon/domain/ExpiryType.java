package com.hapbang.coupon.domain;

/** 발급 쿠폰의 만료 방식 */
public enum ExpiryType {
    /** 최초 접수 시각 + N일 */
    RELATIVE,
    /** 고정 만료 시각 */
    FIXED
}
