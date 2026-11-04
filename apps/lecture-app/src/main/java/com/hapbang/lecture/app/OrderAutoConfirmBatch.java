package com.hapbang.lecture.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/** 자동 구매 확정 배치. 기본 10분마다 실행한다 (설계 10장, D41). 처리는 {@link OrderAutoConfirmUseCase}가 한다 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class OrderAutoConfirmBatch {

    private final OrderAutoConfirmUseCase orderAutoConfirmUseCase;

    @Scheduled(cron = "${lecture.batch.auto-confirm-cron:0 */10 * * * *}", zone = "Asia/Seoul")
    void run() {
        orderAutoConfirmUseCase.confirmDueItems();
    }
}
