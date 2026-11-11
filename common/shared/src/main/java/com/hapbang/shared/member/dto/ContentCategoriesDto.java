package com.hapbang.shared.member.dto;

import java.util.List;
import java.util.Objects;

/**
 * 콘텐츠 분야 전체 목록 (#769). active가 꺼진 분야도 포함하고, 코드 오름차순이다
 *
 * @param items 분야 목록
 */
public record ContentCategoriesDto(List<ContentCategoryDto> items) {

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다. null 항목도 받지 않는다
    public ContentCategoriesDto {
        items = List.copyOf(Objects.requireNonNull(items, "items는 필수입니다."));
    }
}
