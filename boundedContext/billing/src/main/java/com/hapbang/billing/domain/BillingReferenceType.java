package com.hapbang.billing.domain;

// 원장·보류·보류 상세가 참조하는 강의·출연·Billing 거래 대상의 종류를 구분한다.
public enum BillingReferenceType {
    LECTURE_ORDER,
    LECTURE_ORDER_ITEM,
    APPEARANCE_REQUEST,
    APPEARANCE_RECRUIT,
    RECRUIT_APPEARANCE,
    POINT_CHARGE,
    POINT_REFUND_REQUEST,
    EXCHANGE,
    SETTLEMENT
}
