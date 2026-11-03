package com.hapbang.billing.app;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.billing.global.scheduling.BillingJob;
import com.hapbang.billing.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.billing.global.scheduling.JobLock;

import lombok.RequiredArgsConstructor;

// 지갑 이벤트 재발행 배치. 기본 매분 5초에 실행한다(#497, 출연 앱과 같은 주기). 처리는 BillingEventRepublishUseCase가 한다.
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class BillingEventRepublishBatch {

    private final BillingEventRepublishUseCase billingEventRepublishUseCase;
    private final JobLock jobLock;

    @Scheduled(cron = "${hapbang.billing.event.republish-cron:5 * * * * *}", zone = "Asia/Seoul")
    void run() {
        // 배포 중 두 pod가 겹쳐도 한 곳만 실행한다. 락을 잡지 못하면 이번 회차를 건너뛴다(#598)
        jobLock.runOrSkip(BillingJob.EVENT_REPUBLISH, () -> billingEventRepublishUseCase.republishDue(Instant.now()));
    }
}
