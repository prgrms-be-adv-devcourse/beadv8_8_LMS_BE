package com.hapbang.customersupport.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 문의 유형.
 * 지금은 분류만 한다. 노쇼 신고·강의 내용 다름 이의 제기도 대상 거래 연결과 지급 중단 없이 일반 문의처럼 접수된다.
 */
@Getter
@RequiredArgsConstructor
public enum InquiryCategory {
    NO_SHOW_REPORT("노쇼 신고"),
    LECTURE_CONTENT_DISPUTE("강의 내용 다름 이의 제기"),
    SERVICE_COMPLAINT("서비스 불만"),
    OTHER("기타");

    private final String label;
}
