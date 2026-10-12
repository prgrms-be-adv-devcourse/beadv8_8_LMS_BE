package com.hapbang.lecture.in.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.LectureStatus;

public record InspectionLectureResponse(
        Long lectureId,
        Long sellerUserId,
        String sellerNickname,
        String title,
        long priceHp,
        LectureStatus status,
        LocalDateTime inspectionRequestedAt
) {
}
