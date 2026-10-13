package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자 본인이 등록한 강의 (모든 상태)")
public record MyLectureResponse(
        Long lectureId,
        String title,
        long priceHp,
        @Schema(description = "썸네일 조회 URL (Presigned GET, 1시간 유효). 썸네일이 없거나 볼 수 없으면 null", nullable = true)
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

    public static MyLectureResponse of(Lecture lecture, String thumbnailUrl) {
        return new MyLectureResponse(lecture.getId(), lecture.getTitle(), lecture.getPriceHp(), thumbnailUrl,
                lecture.getStatus(), lecture.getRejectionReason(), lecture.isDeleted(),
                lecture.getInspectionRequestedAt(), lecture.getInspectedAt(), lecture.getCreatedAt());
    }
}
