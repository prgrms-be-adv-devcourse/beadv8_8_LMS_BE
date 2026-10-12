package com.hapbang.lecture.in.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReviewResponse(
        Long reviewId,
        Long reviewerUserId,
        @Schema(description = "작성자 닉네임. 탈퇴 회원은 \"탈퇴한 회원\"")
        String reviewerNickname,
        int rating,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
