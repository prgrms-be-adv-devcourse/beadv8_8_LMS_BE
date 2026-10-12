package com.hapbang.lecture.domain;

/**
 * 강의 등록·콘텐츠 교체 시 받는 콘텐츠 한 개. 순서는 목록 순서로 정한다.
 */
// TODO(file): FileResource가 생기면 파일이 업로드 완료(UPLOADED) 상태이고 업로더가 판매자 본인인지 검사한다.
public record LectureContentSpec(
        Long fileId,
        String title,
        ContentType contentType
) {
}
