package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.Lecture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 재검수 신청. 반려 → 검수 대기 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureRequestInspectionUseCase {

    private final LectureSupport lectureSupport;
    private final LectureSyncEvents lectureSyncEvents;

    public void requestInspection(Long sellerUserId, Long lectureId) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        lecture.requestInspection(sellerUserId, LocalDateTime.now());
        lectureSyncEvents.lectureChanged(lecture);
        AfterCommit.run(() -> {
            log.info("강의 검수를 요청했다 lectureId={}, sellerUserId={}", lectureId, sellerUserId);
        });
    }
}
