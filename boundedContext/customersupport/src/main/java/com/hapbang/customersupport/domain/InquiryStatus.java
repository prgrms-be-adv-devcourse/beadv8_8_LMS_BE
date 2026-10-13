package com.hapbang.customersupport.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 문의 상태. 답변을 달면 ANSWERED, 답변을 모두 지우면 다시 WAITING이 된다 */
@Getter
@RequiredArgsConstructor
public enum InquiryStatus {
    WAITING("답변 대기"),
    ANSWERED("답변 완료");

    private final String label;
}
