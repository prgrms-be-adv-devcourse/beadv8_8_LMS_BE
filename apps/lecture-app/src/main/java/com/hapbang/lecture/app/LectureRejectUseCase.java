package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** 검수 반려. 검수 대기 → 반려 (사유 필수) */
@Service
@RequiredArgsConstructor
public class LectureRejectUseCase {

    private final LectureSupport lectureSupport;

    public void reject(Long adminUserId, Long lectureId, String reason) {
        lectureSupport.findLecture(lectureId).reject(adminUserId, reason, LocalDateTime.now());
    }
}
