package com.hapbang.member.in;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** 페이지 응답 (Spring Page를 그대로 내보내지 않는다). 다른 모듈도 쓰게 되면 global로 옮긴다 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
