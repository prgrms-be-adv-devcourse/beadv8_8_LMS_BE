package com.hapbang.lecture.domain;

/**
 * 구매 확정 방식. 직접 확정, 첫 재생(재생 동의), 구매일 다음 날부터 7일 경과 중 먼저 온 것.
 */
public enum ConfirmationType {
    DIRECT,
    FIRST_PLAY,
    AUTO
}
