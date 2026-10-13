package com.hapbang.lecture.domain;

/**
 * 강의 파일 업로드 상태. 삭제는 상태가 아니라 {@code deletedAt}으로 표시한다.
 */
public enum FileStatus {
    /** 업로드 URL만 발급했다. 강의에 연결할 수 없다 */
    PENDING,
    /** S3에 올라간 것을 확인했다 */
    UPLOADED
}
