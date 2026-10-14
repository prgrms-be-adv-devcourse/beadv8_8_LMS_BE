package com.hapbang.lecture.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/** 지급 배치. 기본 매일 01:00에 실행한다 (설계 10장, D49). 처리는 {@link OrderPayoutUseCase}가 한다 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class OrderPayoutBatch {

    private final OrderPayoutUseCase orderPayoutUseCase;

    @Scheduled(cron = "${lecture.batch.payout-cron:0 0 1 * * *}", zone = "Asia/Seoul")
    void run() {
        orderPayoutUseCase.payOutDueItems();
    }
}
