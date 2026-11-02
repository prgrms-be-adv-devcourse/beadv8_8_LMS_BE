package com.hapbang.billing.app;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.billing.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

// 지갑 이벤트 재발행 배치. 기본 매분 5초에 실행한다(#497, 출연 앱과 같은 주기). 처리는 BillingEventRepublishUseCase가 한다.
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class BillingEventRepublishBatch {

    private final BillingEventRepublishUseCase billingEventRepublishUseCase;

    @Scheduled(cron = "${hapbang.billing.event.republish-cron:5 * * * * *}", zone = "Asia/Seoul")
    void run() {
        billingEventRepublishUseCase.republishDue(Instant.now());
    }
}
