package com.hapbang.shared.appearance.dto;

import java.util.List;
import java.util.Objects;

/**
 * 회원이 받은 공개 출연 리뷰 한 페이지 (#514). 출연 API의 페이지 응답({@code content} + {@code page}) 모양 그대로다.
 *
 * @param content 이 페이지의 리뷰. 최신 공개순
 * @param page    페이지 정보
 */
public record AppearanceReceivedReviewPageDto(List<AppearanceReceivedReviewDto> content, PageMetadata page) {

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다. null 항목도 받지 않는다
    public AppearanceReceivedReviewPageDto {
        content = List.copyOf(Objects.requireNonNull(content, "content는 필수입니다."));
        Objects.requireNonNull(page, "page는 필수입니다.");
    }

    /**
     * @param size          페이지 크기
     * @param number        페이지 번호 (0부터)
     * @param totalElements 받은 공개 리뷰 전체 건수
     * @param totalPages    전체 페이지 수
     */
    public record PageMetadata(long size, long number, long totalElements, long totalPages) {
    }
}
