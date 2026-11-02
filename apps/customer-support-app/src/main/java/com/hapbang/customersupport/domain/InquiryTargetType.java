package com.hapbang.customersupport.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 문의 대상의 종류. 대상 ID는 다른 모듈의 ID라 종류와 함께 저장한다 */
@Getter
@RequiredArgsConstructor
public enum InquiryTargetType {
    LECTURE_ORDER_ITEM("강의 주문 항목");

    private final String label;
}
