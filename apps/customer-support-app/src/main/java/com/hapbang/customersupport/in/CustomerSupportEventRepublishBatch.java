package com.hapbang.customersupport.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.customersupport.app.CustomerSupportEventRepublishUseCase;
import com.hapbang.customersupport.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/** 고객센터 이벤트 Kafka 재발행 배치. 기본 매분 5초에 실행한다 (#279) */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class CustomerSupportEventRepublishBatch {

    private final CustomerSupportEventRepublishUseCase customerSupportEventRepublishUseCase;

    @Scheduled(cron = "${customersupport.batch.event-republish-cron:5 * * * * *}", zone = "Asia/Seoul")
    void run() {
        customerSupportEventRepublishUseCase.republishDue();
    }
}
