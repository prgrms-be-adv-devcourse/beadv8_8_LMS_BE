package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** 재검수 신청. 반려 → 검수 대기 */
@Service
@RequiredArgsConstructor
public class LectureRequestInspectionUseCase {

    private final LectureSupport lectureSupport;

    public void requestInspection(Long sellerUserId, Long lectureId) {
        lectureSupport.findLecture(lectureId).requestInspection(sellerUserId, LocalDateTime.now());
    }
}
