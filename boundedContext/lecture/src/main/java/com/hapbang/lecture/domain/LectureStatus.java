package com.hapbang.lecture.domain;

/**
 * 강의 검수 상태. 삭제는 상태가 아니라 {@code deletedAt}으로 표시한다.
 */
public enum LectureStatus {
    PENDING_INSPECTION,
    REJECTED,
    ON_SALE
}
