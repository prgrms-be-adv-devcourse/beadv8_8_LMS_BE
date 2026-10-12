package com.hapbang.lecture.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "강의 목록 한 줄 (판매 중 강의만)")
public record LectureSummaryResponse(
        Long lectureId,
        String title,
        long priceHp,
        @Schema(description = "썸네일 Presigned GET URL", nullable = true)
        String thumbnailUrl,
        Long sellerUserId,
        @Schema(description = "판매자 닉네임. 탈퇴 회원은 \"탈퇴한 회원\"")
        String sellerNickname,
        @Schema(description = "평균 평점. 반환된 항목의 리뷰는 평균·개수에서 뺀다. 리뷰가 없으면 null", nullable = true)
        Double averageRating,
        long reviewCount
) {
}
