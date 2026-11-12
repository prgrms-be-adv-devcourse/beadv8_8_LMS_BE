package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;

import lombok.extern.slf4j.Slf4j;

/** 검수 반려. 검수 대기 → 반려 (사유 필수). 반려 시각은 알림용 반려 이벤트의 사건 시각이라 강의 시계(KST)로 정한다 (#872) */
@Slf4j
@Service
public class LectureRejectUseCase {

    private final LectureSupport lectureSupport;
    private final LectureSyncEvents lectureSyncEvents;
    private final LectureInspectionEvents lectureInspectionEvents;
    private final Clock clock;

    public LectureRejectUseCase(LectureSupport lectureSupport, LectureSyncEvents lectureSyncEvents,
                                LectureInspectionEvents lectureInspectionEvents,
                                @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureSupport = lectureSupport;
        this.lectureSyncEvents = lectureSyncEvents;
        this.lectureInspectionEvents = lectureInspectionEvents;
        this.clock = clock;
    }

    public void reject(Long adminUserId, Long lectureId, String reason) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        lecture.reject(adminUserId, reason, LocalDateTime.now(clock));
        lectureSyncEvents.lectureChanged(lecture);
        lectureInspectionEvents.rejected(lecture);
        // 반려 사유는 관리자가 쓴 본문이라 남기지 않는다(조회는 관리자 API)
        AfterCommit.run(() -> {
            log.info("강의 검수를 반려했다 lectureId={}, adminUserId={}", lectureId, adminUserId);
        });
    }
}
