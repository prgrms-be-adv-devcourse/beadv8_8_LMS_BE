package com.hapbang.billing.app;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.billing.global.scheduling.BillingJob;
import com.hapbang.billing.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.billing.global.scheduling.JobLock;

import lombok.RequiredArgsConstructor;

// 변경된 지갑의 잔액 대사 배치 (#607). 기본 매일 04:02:45 KST. 처리는 ChangedWalletReconciliationUseCase가 한다.
// 환전 정산(10~23시)과 겹치지 않고, 5분 단위 분과 복구·재발행 배치의 초(0·5·15·30)를 피한 시각이다.
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class WalletReconciliationBatch {

    private final ChangedWalletReconciliationUseCase changedWalletReconciliationUseCase;
    private final JobLock jobLock;

    @Scheduled(cron = "${hapbang.billing.reconciliation.cron:45 2 4 * * *}", zone = "Asia/Seoul")
    void run() {
        // 배포 중 두 pod가 겹쳐도 한 곳만 실행한다. 락을 잡지 못하면 이번 회차를 건너뛴다(#598)
        jobLock.runOrSkip(BillingJob.WALLET_RECONCILIATION,
                () -> changedWalletReconciliationUseCase.reconcileChanged(Instant.now()));
    }
}
