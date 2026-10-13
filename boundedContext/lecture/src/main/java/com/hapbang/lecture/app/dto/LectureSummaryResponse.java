package com.hapbang.lecture.app.dto;

import com.hapbang.lecture.domain.Lecture;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "강의 목록 한 줄 (판매 중 강의만)")
public record LectureSummaryResponse(
        Long lectureId,
        String title,
        long priceHp,
        @Schema(description = "썸네일 조회 URL (Presigned GET, 1시간 유효). 썸네일이 없거나 볼 수 없으면 null", nullable = true)
        String thumbnailUrl,
        Long sellerUserId,
        @Schema(description = "판매자 닉네임. 탈퇴 회원은 \"탈퇴한 회원\"")
        String sellerNickname,
        @Schema(description = "평균 평점. 반환된 항목의 리뷰는 평균·개수에서 뺀다. 리뷰가 없으면 null", nullable = true)
        Double averageRating,
        long reviewCount
) {

    // TODO: 평점·리뷰 수는 6단계(리뷰)에서 채운다.
    public static LectureSummaryResponse of(Lecture lecture, String sellerNickname, String thumbnailUrl) {
        return new LectureSummaryResponse(lecture.getId(), lecture.getTitle(), lecture.getPriceHp(), thumbnailUrl,
                lecture.getSellerUserId(), sellerNickname, null, 0);
    }
}
