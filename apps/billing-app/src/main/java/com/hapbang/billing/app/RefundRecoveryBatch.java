package com.hapbang.billing.app;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.billing.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

// 환불 결과 확인 배치. 기본 5분마다 실행한다(#260). 처리는 RefundRecoveryUseCase가 한다.
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class RefundRecoveryBatch {

    private final RefundRecoveryUseCase refundRecoveryUseCase;

    @Scheduled(cron = "${hapbang.billing.refund.recovery-cron:30 */5 * * * *}", zone = "Asia/Seoul")
    void run() {
        refundRecoveryUseCase.recoverStale(Instant.now());
    }
}
