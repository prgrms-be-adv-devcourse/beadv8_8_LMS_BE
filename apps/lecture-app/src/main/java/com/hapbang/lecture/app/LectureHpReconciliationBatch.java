package com.hapbang.lecture.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 서비스 간 HP 대사 배치. 기본 매일 04:30:25(KST)에 실행한다 (D74). 지급 배치(01:00)·출연 대사(04:00:25)와 겹치지 않고,
 * 매분 배치(HP 작업 0초·이벤트 재발행 5초)가 쓰지 않는 25초다. 처리는 {@link LectureHpReconciliationUseCase}가 한다
 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class LectureHpReconciliationBatch {

    private final LectureHpReconciliationUseCase lectureHpReconciliationUseCase;

    @Scheduled(cron = "${lecture.batch.hp-reconciliation-cron:25 30 4 * * *}", zone = "Asia/Seoul")
    void run() {
        lectureHpReconciliationUseCase.runScheduled();
    }
}
