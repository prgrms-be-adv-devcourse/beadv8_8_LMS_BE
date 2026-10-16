package com.hapbang.shared.lecture.dto;

import java.util.List;

/**
 * 판매 수익 목록 한 페이지 (#300). 강의 API의 페이지 응답({@code content} + {@code page}) 모양 그대로다.
 *
 * @param content 이 페이지의 수익. 상태 변경 최신순
 * @param page    페이지 정보
 */
public record LectureEarningPageDto(List<LectureEarningItemDto> content, PageMetadata page) {

    /**
     * @param size          페이지 크기
     * @param number        페이지 번호 (0부터)
     * @param totalElements 조건에 맞는 전체 건수
     * @param totalPages    전체 페이지 수
     */
    public record PageMetadata(long size, long number, long totalElements, long totalPages) {
    }
}
