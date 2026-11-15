package com.hapbang.shared.lecture.domain;

/**
 * 구매 확정 방식 (#872, D85). 직접 확정·첫 재생(재생 동의)·자동 구매 확정 중 먼저 온 것이다(D34).
 * 자동 구매 확정 시각이 지난 뒤의 직접 확정·재생 동의도 먼저 온 사건인 {@link #AUTO}로 기록한다.
 * 상수 이름은 이벤트 문자열이므로 바꾸지 않는다.
 */
public enum LectureConfirmationType {
    /** 구매자가 직접 확정 */
    DIRECT,
    /** 처음 보기 전 재생 동의로 확정 */
    FIRST_PLAY,
    /** 구매일 다음 날부터 7일이 지나 자동 확정 */
    AUTO
}
