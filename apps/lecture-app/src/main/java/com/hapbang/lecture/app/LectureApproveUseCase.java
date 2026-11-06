package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.Lecture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 검수 승인. 검수 대기 → 판매 중 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureApproveUseCase {

    private final LectureSupport lectureSupport;
    private final LectureSyncEvents lectureSyncEvents;

    public void approve(Long adminUserId, Long lectureId) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        lecture.approve(adminUserId, LocalDateTime.now());
        lectureSyncEvents.lectureChanged(lecture);
        AfterCommit.run(() -> {
            log.info("강의 검수를 승인했다 lectureId={}, adminUserId={}", lectureId, adminUserId);
        });
    }
}
