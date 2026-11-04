package com.hapbang.customersupport.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 강의 내용 다름 이의 제기에 대한 관리자 판단 */
@Getter
@RequiredArgsConstructor
public enum ObjectionResult {
    ACCEPTED("인정"),
    REJECTED("기각");

    private final String label;

    public static ObjectionResult of(boolean accepted) {
        return accepted ? ACCEPTED : REJECTED;
    }
}
