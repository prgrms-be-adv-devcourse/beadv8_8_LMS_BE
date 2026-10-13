package com.hapbang.lecture.domain;

/**
 * 강의 파일 업로드 상태. 삭제는 상태가 아니라 {@code deletedAt}으로 표시한다.
 */
public enum FileStatus {
    /** 파일 정보만 등록했다. 본문을 올리기 전이라 강의에 연결할 수 없다 */
    PENDING,
    /** 서버가 본문을 S3에 저장했다 */
    UPLOADED
}
