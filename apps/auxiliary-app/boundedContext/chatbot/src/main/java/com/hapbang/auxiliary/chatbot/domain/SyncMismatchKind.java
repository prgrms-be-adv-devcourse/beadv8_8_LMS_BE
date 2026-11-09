package com.hapbang.auxiliary.chatbot.domain;

/** 싱크가 찾은 어긋남 (#716). 싱크 회차에서만 기록한다(이벤트 반영은 기록하지 않는다) */
public enum SyncMismatchKind {

    /** 원본에만 있다 → 넣었다 */
    ONLY_IN_SOURCE,
    /** 원본 version이 크고 값이 다르다 → 반영했다 */
    SOURCE_NEWER,
    /** version이 같은데(또는 version이 없는데) 값이 다르다 → 원본으로 고쳤다 */
    SAME_VERSION_DIFFERENT,
    /** 우리 version이 크다 → 무시했다. 싱크가 읽은 뒤 이벤트가 먼저 들어온 경우 */
    OURS_NEWER,
    /** 끝까지 성공한 싱크에서 원본에 없었다 → 추천에서 뺀다 */
    MISSING_IN_SOURCE
}
