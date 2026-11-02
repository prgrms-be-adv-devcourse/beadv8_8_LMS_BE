package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceReview;

import io.swagger.v3.oas.annotations.media.Schema;

/** 출연 리뷰 1건 (출연 상세의 내 리뷰·받은 리뷰, #514) */
public record AppearanceReviewResDto(
        @Schema(description = "리뷰 ID") Long id,
        @Schema(description = "별점 (1~5 정수)") int rating,
        @Schema(description = "본문 (선택). 쓰지 않았으면 null") String content,
        @Schema(description = "공개 여부. 상대도 리뷰를 써서 함께 공개됐으면 true") boolean published,
        @Schema(description = "공개 시각. 공개 전이면 null") LocalDateTime publishedAt,
        @Schema(description = "작성 시각") LocalDateTime createdAt
) {

    public static AppearanceReviewResDto from(AppearanceReview review) {
        return new AppearanceReviewResDto(review.getId(), review.getRating(), review.getContent(),
                review.isPublished(), review.getPublishedAt(), review.getCreatedAt());
    }
}
