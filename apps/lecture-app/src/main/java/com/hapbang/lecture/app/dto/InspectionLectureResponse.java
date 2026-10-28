package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.Lecture;
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

    public static InspectionLectureResponse of(Lecture lecture, String sellerNickname) {
        return new InspectionLectureResponse(lecture.getId(), lecture.getSellerUserId(), sellerNickname,
                lecture.getTitle(), lecture.getPriceHp(), lecture.getStatus(), lecture.getInspectionRequestedAt());
    }
}
