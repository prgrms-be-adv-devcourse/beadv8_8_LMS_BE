package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** 검수 승인. 검수 대기 → 판매 중 */
@Service
@RequiredArgsConstructor
public class LectureApproveUseCase {

    private final LectureSupport lectureSupport;

    public void approve(Long adminUserId, Long lectureId) {
        lectureSupport.findLecture(lectureId).approve(adminUserId, LocalDateTime.now());
    }
}
