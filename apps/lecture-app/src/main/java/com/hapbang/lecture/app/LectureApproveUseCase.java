package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;

import lombok.extern.slf4j.Slf4j;

/** 검수 승인. 검수 대기 → 판매 중. 승인 시각은 알림용 승인 이벤트의 사건 시각이라 강의 시계(KST)로 정한다 (#872) */
@Slf4j
@Service
public class LectureApproveUseCase {

    private final LectureSupport lectureSupport;
    private final LectureSyncEvents lectureSyncEvents;
    private final LectureInspectionEvents lectureInspectionEvents;
    private final Clock clock;

    public LectureApproveUseCase(LectureSupport lectureSupport, LectureSyncEvents lectureSyncEvents,
                                 LectureInspectionEvents lectureInspectionEvents,
                                 @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureSupport = lectureSupport;
        this.lectureSyncEvents = lectureSyncEvents;
        this.lectureInspectionEvents = lectureInspectionEvents;
        this.clock = clock;
    }

    public void approve(Long adminUserId, Long lectureId) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        lecture.approve(adminUserId, LocalDateTime.now(clock));
        lectureSyncEvents.lectureChanged(lecture);
        lectureInspectionEvents.approved(lecture);
        AfterCommit.run(() -> {
            log.info("강의 검수를 승인했다 lectureId={}, adminUserId={}", lectureId, adminUserId);
        });
    }
}
