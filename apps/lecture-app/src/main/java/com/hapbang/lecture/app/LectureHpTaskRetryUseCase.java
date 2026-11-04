package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpTask;

import lombok.extern.slf4j.Slf4j;

/**
 * 관리자의 HP 작업 재시도: {@code DEAD} → {@code PENDING} (D57 (9)). 원인을 먼저 고친 뒤 부른다.
 * 바로 실행하지 않고 다음 재시도 배치(기본 1분 안)가 같은 요청 ID로 다시 보낸다. 호출한 쪽 트랜잭션에서 실행한다.
 */
@Slf4j
@Service
public class LectureHpTaskRetryUseCase {

    private final LectureHpTaskSupport lectureHpTaskSupport;
    private final Clock clock;

    public LectureHpTaskRetryUseCase(LectureHpTaskSupport lectureHpTaskSupport,
                                     @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureHpTaskSupport = lectureHpTaskSupport;
        this.clock = clock;
    }

    public void retry(Long adminUserId, Long taskId) {
        LectureHpTask task = lectureHpTaskSupport.getById(taskId);
        task.retryManually(adminUserId, LocalDateTime.now(clock));
        AfterCommit.run(() -> {
            log.info("관리자가 DEAD HP 작업을 다시 시도하게 했다 taskId={}, adminUserId={}, lastErrorCode={}, "
                            + "totalAttemptCount={}", task.getId(), adminUserId, task.getLastErrorCode(),
                    task.getTotalAttemptCount());
        });
    }
}
