package com.hapbang.auxiliary.chatbot.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** {@code chatbot.index_task.action}: 색인 요청의 종류 (#717). 실행은 #718·#719 */
@Getter
@RequiredArgsConstructor
public enum IndexAction {

    UPSERT("문서 전체를 넣거나 바꾼다"),
    DELETE("문서를 지운다"),
    PARTIAL("문서 일부 필드만 바꾼다");

    private final String description;
}
