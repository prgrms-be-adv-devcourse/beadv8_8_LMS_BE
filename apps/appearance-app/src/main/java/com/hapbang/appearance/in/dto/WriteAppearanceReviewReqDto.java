package com.hapbang.appearance.in.dto;

import com.hapbang.appearance.domain.AppearanceReview;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 출연 리뷰 작성 요청 (#512). 별점은 필수, 본문은 선택이다. 공백뿐인 본문은 도메인이 비운다.
 */
public record WriteAppearanceReviewReqDto(
        @Schema(description = "별점 (필수, 1~5 정수)", example = "5")
        @NotNull(message = "별점을 입력해 주세요.")
        @Min(value = AppearanceReview.RATING_MIN, message = "별점은 1~5 사이의 정수여야 합니다.")
        @Max(value = AppearanceReview.RATING_MAX, message = "별점은 1~5 사이의 정수여야 합니다.")
        Integer rating,

        @Schema(description = "본문 (선택, 1,000자 이하). 공백뿐이면 비운다", example = "준비를 꼼꼼히 해 주셔서 방송이 매끄러웠습니다.")
        @Size(max = AppearanceReview.CONTENT_MAX, message = "리뷰 본문은 1,000자 이하여야 합니다.")
        String content
) {
}
