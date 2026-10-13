package com.hapbang.lecture.domain;

/**
 * 강의 등록·콘텐츠 교체 시 받는 콘텐츠 한 개. 순서는 목록 순서로 정한다.
 * 파일이 판매자 본인이 올린 업로드 완료 파일이고 용도가 맞는지는 app의 LectureFileSupport가 검사한다.
 */
public record LectureContentSpec(
        Long fileId,
        String title,
        ContentType contentType
) {
}
