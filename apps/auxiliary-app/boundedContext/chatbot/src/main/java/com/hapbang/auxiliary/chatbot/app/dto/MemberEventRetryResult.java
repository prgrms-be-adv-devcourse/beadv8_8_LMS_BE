package com.hapbang.auxiliary.chatbot.app.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 관리자가 다시 처리한 회원 이벤트 한 건의 결과 (#976) */
@Getter
@RequiredArgsConstructor
public enum MemberEventRetryResult {

    SUCCEEDED("반영함. 목록에서 처리됨(RESOLVED)으로 바뀐다"),
    FAILED("또 실패함. 처리 대기(PENDING)로 남고 이유가 바뀐다"),
    ALREADY_RESOLVED("이미 처리된 기록"),
    NOT_FOUND("없는 기록"),
    NOT_REPLAYABLE("읽지 못했거나 필수 값이 없어 다시 처리할 수 없음");

    private final String description;
}
