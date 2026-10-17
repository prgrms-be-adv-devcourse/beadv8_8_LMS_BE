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
        @Schema(description = "썸네일 조회 URL (Presigned GET, 1시간 유효). 썸네일이 없거나 볼 수 없으면 null", nullable = true)
        String thumbnailUrl,
        @Schema(description = "썸네일 파일 ID. 판매자·관리자에게만 내려준다. 정보 수정에서 썸네일을 유지하려면 이 값을 다시 보낸다 (D53)",
                nullable = true)
        Long thumbnailFileId,
        LectureStatus status,
        @Schema(description = "판매자·관리자에게만 내려준다", nullable = true)
        String rejectionReason,
        @Schema(description = "삭제된 강의. 판매자·관리자·보유 중인 구매자만 조회할 수 있다")
        boolean deleted,
        @Schema(description = "평균 평점(소수 첫째 자리까지 반올림). 반환된 항목의 리뷰는 평균·개수에서 뺀다. 리뷰가 없으면 null", nullable = true)
        Double averageRating,
        long reviewCount,
        List<LectureContentResponse> contents,
        LocalDateTime createdAt
) {

    /** 판매자 본인(일반 상세)·관리자(검수 상세)면 {@code sellerView}로 반려 사유와 파일 ID를 보여 준다 (D53) */
    public static LectureDetailResponse of(Lecture lecture, String sellerNickname, String thumbnailUrl,
                                           LectureRating rating, boolean sellerView) {
        return new LectureDetailResponse(
                lecture.getId(),
                lecture.getSellerUserId(),
                sellerNickname,
                lecture.getTitle(),
                lecture.getDescription(),
                lecture.getPriceHp(),
                thumbnailUrl,
                sellerView ? lecture.getThumbnailFileId() : null,
                lecture.getStatus(),
                sellerView ? lecture.getRejectionReason() : null,
                lecture.isDeleted(),
                rating.averageRating(),
                rating.reviewCount(),
                lecture.getContents().stream().map(content -> LectureContentResponse.from(content, sellerView)).toList(),
                lecture.getCreatedAt()
        );
    }
}
