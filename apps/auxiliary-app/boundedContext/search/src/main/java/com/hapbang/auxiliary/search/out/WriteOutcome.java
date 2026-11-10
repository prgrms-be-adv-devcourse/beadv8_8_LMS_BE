package com.hapbang.auxiliary.search.out;

/** 문서 쓰기 결과(#771). 로그·지표에 남긴다 */
public enum WriteOutcome {
    /** 문서를 새로 만들었다 */
    CREATED,
    /** 문서를 바꿨다 */
    UPDATED,
    /** 이미 같거나 더 새 값이라 바꾸지 않았다(늦게 온 이벤트, 같은 이벤트 두 번, 같은 통계) */
    NOOP,
    /** 문서가 없어 건너뛰었다(통계 동기화) */
    MISSING,
    /** 쓰지 못했다(통계 동기화, 다음 회차에 다시 쓴다) */
    FAILED
}
