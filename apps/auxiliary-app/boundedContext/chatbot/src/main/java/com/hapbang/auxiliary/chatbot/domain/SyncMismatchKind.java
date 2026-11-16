package com.hapbang.auxiliary.chatbot.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 싱크가 찾은 어긋남 (#716). 싱크 회차에서만 기록한다(이벤트 반영은 기록하지 않는다) */
@Getter
@RequiredArgsConstructor
public enum SyncMismatchKind {

    /** 원본에만 있다 → 넣었다 */
    ONLY_IN_SOURCE("원본에만 있어 넣음"),
    /** 원본 version이 크고 값이 다르다 → 반영했다 */
    SOURCE_NEWER("원본이 새것이라 반영함"),
    /** version이 같은데(또는 version이 없는데) 값이 다르다 → 원본으로 고쳤다 */
    SAME_VERSION_DIFFERENT("같은 version인데 값이 달라 원본으로 고침"),
    /** 우리 version이 크다 → 무시했다. 싱크가 읽은 뒤 이벤트가 먼저 들어온 경우 */
    OURS_NEWER("우리 version이 커서 무시함"),
    /** 끝까지 성공한 싱크에서 원본에 없었다 → 추천에서 뺀다 */
    MISSING_IN_SOURCE("원본에 없어 추천에서 뺌");

    private final String description;
}
