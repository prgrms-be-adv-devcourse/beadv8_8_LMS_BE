package com.hapbang.auxiliary.chatbot.domain;

/** {@code chatbot.index_task.action}: 색인 요청의 종류 (#717). 실행은 #718·#719 */
public enum IndexAction {

    /** 문서 전체를 넣거나 바꾼다 */
    UPSERT,
    /** 문서를 지운다 */
    DELETE,
    /** 문서 일부 필드만 바꾼다 */
    PARTIAL
}
