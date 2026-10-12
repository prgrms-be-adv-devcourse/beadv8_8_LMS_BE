package com.hapbang.lecture.app.dto;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * 페이지 목록 응답. page는 0부터 시작한다.
 */
// TODO: 팀 공통 응답 포맷이 정해지면 교체한다 (AGENTS.md 11장 미정 항목).
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.hasNext());
    }
}
