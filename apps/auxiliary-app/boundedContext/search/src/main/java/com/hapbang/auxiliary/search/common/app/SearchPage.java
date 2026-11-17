package com.hapbang.auxiliary.search.common.app;

import java.util.List;

import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 검색 결과 한 페이지와 검색어 제안(#977). JSON은 앱의 페이지 응답(Spring Data {@code VIA_DTO}: {@code content}·{@code page})과 같은 모양에
 * {@code correctedKeyword} 칸 하나를 더했다. 그래서 검색 결과를 읽던 화면은 그대로 쓸 수 있다.
 *
 * @param correctedKeyword "이 검색어를 찾으셨나요?"에 보일 고친 검색어. 없으면 null. 자동완성 후보({@code /suggestions})와 다른 것이라 이름을 나눴다
 */
public record SearchPage<T>(
        List<T> content,
        PageInfo page,
        @Schema(description = "검색어에 오타가 있을 때 고친 검색어(\"이 검색어를 찾으셨나요?\", 누르면 이 검색어로 다시 검색). 없으면 null",
                example = "프리미어", nullable = true) String correctedKeyword) {

    /** {@code VIA_DTO}의 {@code page}와 같은 칸 */
    public record PageInfo(long size, long number, long totalElements, long totalPages) {
    }

    public static <T> SearchPage<T> of(Page<T> page, String correctedKeyword) {
        return new SearchPage<>(page.getContent(),
                new PageInfo(page.getSize(), page.getNumber(), page.getTotalElements(), page.getTotalPages()), correctedKeyword);
    }
}
