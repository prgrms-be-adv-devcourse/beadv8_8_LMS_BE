package com.hapbang.coupon.domain;

/** 할인 방식 */
public enum DiscountType {
    /** 정률. 할인 값은 퍼센트 정수(1~100)다 */
    RATE,
    /** 정액. 할인 값은 HP다 */
    AMOUNT
}
