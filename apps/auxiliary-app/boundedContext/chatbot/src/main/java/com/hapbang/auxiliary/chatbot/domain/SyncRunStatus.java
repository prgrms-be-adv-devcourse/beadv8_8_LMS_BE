package com.hapbang.auxiliary.chatbot.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 싱크 회차 상태 (#716) */
@Getter
@RequiredArgsConstructor
public enum SyncRunStatus {

    RUNNING("실행 중"),
    /** 원본을 처음부터 끝까지 읽었다. 이 회차만 원본 없음을 표시한다 */
    SUCCEEDED("원본을 끝까지 읽음"),
    /** 원본 호출 실패·잘못된 응답 등으로 멈췄다. 원본 없음을 표시하지 않는다 */
    FAILED("원본 호출 실패 등으로 멈춤"),
    /** 다른 실행이 락을 쥐고 있거나 락을 시도하지 못해 실행하지 않았다 */
    SKIPPED("락을 얻지 못해 실행하지 않음");

    private final String description;
}
