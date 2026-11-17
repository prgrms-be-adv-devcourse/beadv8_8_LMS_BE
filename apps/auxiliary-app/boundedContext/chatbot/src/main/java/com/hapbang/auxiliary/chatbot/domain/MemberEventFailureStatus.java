package com.hapbang.auxiliary.chatbot.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 회원 이벤트 실패 기록의 상태 (#976) */
@Getter
@RequiredArgsConstructor
public enum MemberEventFailureStatus {

    /** 아직 반영하지 못했다. 관리자가 다시 처리해야 한다 */
    PENDING("처리 대기"),
    /** 관리자가 다시 처리해 반영했다 */
    RESOLVED("처리됨");

    private final String description;
}
