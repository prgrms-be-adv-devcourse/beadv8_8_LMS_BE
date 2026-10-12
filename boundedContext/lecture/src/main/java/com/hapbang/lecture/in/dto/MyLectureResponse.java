package com.hapbang.lecture.in.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.LectureStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자 본인이 등록한 강의 (모든 상태)")
public record MyLectureResponse(
        Long lectureId,
        String title,
        long priceHp,
        @Schema(nullable = true)
        String thumbnailUrl,
        LectureStatus status,
        @Schema(nullable = true)
        String rejectionReason,
        boolean deleted,
        LocalDateTime inspectionRequestedAt,
        @Schema(nullable = true)
        LocalDateTime inspectedAt,
        LocalDateTime createdAt
) {
}
