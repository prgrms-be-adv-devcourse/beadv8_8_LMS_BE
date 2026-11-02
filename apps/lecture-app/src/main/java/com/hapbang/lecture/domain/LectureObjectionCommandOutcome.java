package com.hapbang.lecture.domain;

/** 이의 제기 명령 처리 결과 (#278, D71) */
public enum LectureObjectionCommandOutcome {
    /** 항목에 반영했다 */
    SUCCEEDED,
    /** 업무 규칙으로 거절했다. 항목은 바뀌지 않았다 */
    REFUSED
}
