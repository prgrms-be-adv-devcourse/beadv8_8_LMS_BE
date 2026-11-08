package com.hapbang.billing.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.billing.global.scheduling.BillingJob;
import com.hapbang.billing.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.billing.global.scheduling.JobLock;

import lombok.RequiredArgsConstructor;

// 환전 송금 결과 확인 배치 (#305). 기본 5분마다(15초). 처리는 ExchangePayoutUseCase.recoverStale이 한다(같은 지급 요청 ID로 재확인,
// 마지막 확인 1분 경과·최대 100건, 최초 요청 24시간 뒤 운영자 확인). 공통 배치 스위치와 이 배치 스위치
// (hapbang.billing.exchange.payout.recovery-enabled, 기본 켬)가 모두 켜졌을 때만 빈이 생긴다. 송금 Client가 없으면 아무것도 하지 않는다.
@Component
@ConditionalOnSchedulingEnabled
@ConditionalOnProperty(name = "hapbang.billing.exchange.payout.recovery-enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
class ExchangePayoutRecoveryBatch {

    private final ExchangePayoutUseCase exchangePayoutUseCase;
    private final JobLock jobLock;

    @Scheduled(cron = "${hapbang.billing.exchange.payout.recovery-cron:15 */5 * * * *}", zone = "Asia/Seoul")
    void run() {
        // 배포 중 두 pod가 겹쳐도 한 곳만 실행한다. 락을 잡지 못하면 이번 회차를 건너뛴다(#598)
        jobLock.runOrSkip(BillingJob.EXCHANGE_PAYOUT_RECOVERY, () -> exchangePayoutUseCase.recoverStale());
    }
}
