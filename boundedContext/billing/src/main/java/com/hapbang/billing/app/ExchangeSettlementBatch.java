package com.hapbang.billing.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

// 환전 정산일 자동 송금 배치 (#305). 기본 매일 10:05~23:05 KST 매시 5분. 처리는 ExchangeSettlementUseCase가 한다.
// 공통 배치 스위치(hapbang.scheduling.enabled)와 이 배치 스위치(hapbang.billing.exchange.settlement.enabled, 기본 켬)가 모두 켜졌을 때만
// 빈이 생긴다. 송금 Client를 쓸 수 없는 환경(Mock이 꺼진 dev·prod)에서는 실행해도 송금 없이 건너뛴다.
@Component
@ConditionalOnSchedulingEnabled
@ConditionalOnProperty(name = "hapbang.billing.exchange.settlement.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class ExchangeSettlementBatch {

    private final ExchangeSettlementUseCase exchangeSettlementUseCase;

    @Scheduled(cron = "${hapbang.billing.exchange.settlement.cron:0 5 10-23 * * *}", zone = "Asia/Seoul")
    void run() {
        exchangeSettlementUseCase.run();
    }
}
