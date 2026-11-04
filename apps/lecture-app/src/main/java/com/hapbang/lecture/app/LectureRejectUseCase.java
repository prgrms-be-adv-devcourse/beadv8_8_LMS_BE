package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 검수 반려. 검수 대기 → 반려 (사유 필수) */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureRejectUseCase {

    private final LectureSupport lectureSupport;

    public void reject(Long adminUserId, Long lectureId, String reason) {
        lectureSupport.findLecture(lectureId).reject(adminUserId, reason, LocalDateTime.now());
        // 반려 사유는 관리자가 쓴 본문이라 남기지 않는다(조회는 관리자 API)
        AfterCommit.run(() -> {
            log.info("강의 검수를 반려했다 lectureId={}, adminUserId={}", lectureId, adminUserId);
        });
    }
}
