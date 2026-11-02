package com.hapbang.customersupport.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 강의 내용 다름 이의 제기 문의의 강의 연동 상태 (#279, D60·D71). 이의 제기 문의에만 있다.
 * <pre>
 * FILING ─성공→ FILED ─판단 요청→ RESOLUTION_PENDING ─강의 반영→ RESOLVED
 *   │                │                         └─강의 거절→ RESOLUTION_FAILED
 *   └─거절→ FILE_REJECTED  └─철회 요청→ WITHDRAWAL_PENDING ─강의 반영→ WITHDRAWN (문의 삭제)
 *                                                       └─강의 거절→ WITHDRAWAL_FAILED
 * </pre>
 * {@code *_FAILED}는 같은 명령을 다시 보내도 같은 거절이라 운영 복구(새 명령 ID, #267)를 기다린다.
 */
@Getter
@RequiredArgsConstructor
public enum ObjectionLinkStatus {
    FILING("접수 처리 중"),
    FILED("접수됨"),
    FILE_REJECTED("접수 불가"),
    RESOLUTION_PENDING("판단 처리 중"),
    RESOLVED("판단 완료"),
    RESOLUTION_FAILED("판단 처리 지연"),
    WITHDRAWAL_PENDING("철회 처리 중"),
    WITHDRAWN("철회됨"),
    WITHDRAWAL_FAILED("철회 처리 지연");

    private final String label;

    /** 강의가 거절해 운영 복구를 기다리는 상태 */
    public boolean isFailed() {
        return this == RESOLUTION_FAILED || this == WITHDRAWAL_FAILED;
    }
}
