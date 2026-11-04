package com.hapbang.lecture.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/** HP 작업 재시도 배치. 기본 1분마다 실행한다 (D57 (7)). 처리는 {@link LectureHpTaskBatchUseCase}가 한다 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class LectureHpTaskBatch {

    private final LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Scheduled(cron = "${lecture.hp-task.retry-cron:0 * * * * *}", zone = "Asia/Seoul")
    void run() {
        lectureHpTaskBatchUseCase.runDue();
    }
}
