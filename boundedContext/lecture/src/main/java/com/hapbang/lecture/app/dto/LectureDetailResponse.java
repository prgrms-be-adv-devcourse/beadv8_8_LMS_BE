package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record LectureDetailResponse(
        Long lectureId,
        Long sellerUserId,
        @Schema(description = "판매자 닉네임. 탈퇴 회원은 \"탈퇴한 회원\"")
        String sellerNickname,
        String title,
        String description,
        long priceHp,
        @Schema(nullable = true)
        String thumbnailUrl,
        LectureStatus status,
        @Schema(description = "판매자·관리자에게만 내려준다", nullable = true)
        String rejectionReason,
        @Schema(description = "삭제된 강의. 판매자·관리자·보유 중인 구매자만 조회할 수 있다")
        boolean deleted,
        @Schema(description = "평균 평점. 반환된 항목의 리뷰는 평균·개수에서 뺀다. 리뷰가 없으면 null", nullable = true)
        Double averageRating,
        long reviewCount,
        List<LectureContentResponse> contents,
        LocalDateTime createdAt
) {

    // TODO: 썸네일 URL은 5단계(S3), 평점·리뷰 수는 6단계(리뷰)에서 채운다.
    public static LectureDetailResponse of(Lecture lecture, String sellerNickname, boolean showRejectionReason) {
        return new LectureDetailResponse(
                lecture.getId(),
                lecture.getSellerUserId(),
                sellerNickname,
                lecture.getTitle(),
                lecture.getDescription(),
                lecture.getPriceHp(),
                null,
                lecture.getStatus(),
                showRejectionReason ? lecture.getRejectionReason() : null,
                lecture.isDeleted(),
                null,
                0,
                lecture.getContents().stream().map(LectureContentResponse::from).toList(),
                lecture.getCreatedAt()
        );
    }
}
