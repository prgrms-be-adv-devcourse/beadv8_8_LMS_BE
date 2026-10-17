package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.LectureReview;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReviewResponse(
        Long reviewId,
        Long reviewerUserId,
        @Schema(description = "작성자 닉네임. 탈퇴 회원은 \"탈퇴한 회원\". 회원 정보가 아직 동기화되지 않았으면 null", nullable = true)
        String reviewerNickname,
        @Schema(description = "평점 (1~5)")
        int rating,
        @Schema(description = "본문. 평점만 남긴 리뷰는 null", nullable = true)
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ReviewResponse of(LectureReview review, String reviewerNickname) {
        return new ReviewResponse(review.getId(), review.getReviewerUserId(), reviewerNickname, review.getRating(),
                review.getContent(), review.getCreatedAt(), review.getUpdatedAt());
    }
}
