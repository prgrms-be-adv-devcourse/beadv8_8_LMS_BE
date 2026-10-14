package com.hapbang.customersupport.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 문의 유형.
 * 강의 내용 다름 이의 제기는 대상 강의 주문 항목을 받아 강의 모듈에 접수한다(지급 중단).
 * 노쇼 신고는 아직 대상 거래 연결 없이 일반 문의처럼 접수된다.
 */
@Getter
@RequiredArgsConstructor
public enum InquiryCategory {
    NO_SHOW_REPORT("노쇼 신고"),
    LECTURE_CONTENT_DISPUTE("강의 내용 다름 이의 제기"),
    SERVICE_COMPLAINT("서비스 불만"),
    OTHER("기타");

    private final String label;

    /** 대상(다른 모듈의 거래)을 함께 받아야 하는 유형인지 */
    public boolean requiresTarget() {
        return this == LECTURE_CONTENT_DISPUTE;
    }
}
