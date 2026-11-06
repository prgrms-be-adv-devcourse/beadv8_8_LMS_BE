package com.hapbang.shared.lecture.dto;

import java.util.Objects;

/**
 * AI 추천용 강의 콘텐츠(영상·자료) 하나 (#712). 콘텐츠 종류·파일은 싣지 않는다.
 *
 * @param id       콘텐츠 ID
 * @param title    콘텐츠 제목
 * @param sequence 강의 안 순서 (1부터)
 */
public record LectureContentSyncDto(Long id, String title, int sequence) {

    public LectureContentSyncDto {
        Objects.requireNonNull(id, "id는 필수입니다.");
        Objects.requireNonNull(title, "title은 필수입니다.");
    }
}
