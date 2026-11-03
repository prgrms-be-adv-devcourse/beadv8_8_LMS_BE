package com.hapbang.billing.app;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.billing.global.scheduling.BillingJob;
import com.hapbang.billing.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.billing.global.scheduling.JobLock;

import lombok.RequiredArgsConstructor;

// 충전 결과 확인 배치. 기본 5분마다 실행한다(#224). 처리는 ChargeRecoveryUseCase가 한다.
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ChargeRecoveryBatch {

    private final ChargeRecoveryUseCase chargeRecoveryUseCase;
    private final JobLock jobLock;

    @Scheduled(cron = "${hapbang.billing.charge.recovery-cron:0 */5 * * * *}", zone = "Asia/Seoul")
    void run() {
        // 배포 중 두 pod가 겹쳐도 한 곳만 실행한다. 락을 잡지 못하면 이번 회차를 건너뛴다(#598)
        jobLock.runOrSkip(BillingJob.CHARGE_RECOVERY, () -> chargeRecoveryUseCase.recoverStale(Instant.now()));
    }
}
