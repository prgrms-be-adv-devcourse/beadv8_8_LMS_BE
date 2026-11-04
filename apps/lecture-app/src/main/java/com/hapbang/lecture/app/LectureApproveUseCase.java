package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 검수 승인. 검수 대기 → 판매 중 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureApproveUseCase {

    private final LectureSupport lectureSupport;

    public void approve(Long adminUserId, Long lectureId) {
        lectureSupport.findLecture(lectureId).approve(adminUserId, LocalDateTime.now());
        log.info("강의 검수를 승인했다 lectureId={}, adminUserId={}", lectureId, adminUserId);
    }
}
