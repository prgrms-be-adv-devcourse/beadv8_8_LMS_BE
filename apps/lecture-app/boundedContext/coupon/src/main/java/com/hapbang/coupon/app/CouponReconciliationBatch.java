package com.hapbang.coupon.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 발급 대사·적체 차단·기록 정리 배치. 앞 회차가 끝난 뒤 {@code hapbang.coupon.reconciliation.interval}(코드 기본값 1분, 측정·합의 전 값)마다
 * 실행한다. 처리는 {@link CouponReconciliationUseCase}가 한다
 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class CouponReconciliationBatch {

    private final CouponReconciliationUseCase reconciliationUseCase;

    @Scheduled(fixedDelayString = "${hapbang.coupon.reconciliation.interval:PT1M}",
            initialDelayString = "${hapbang.coupon.reconciliation.interval:PT1M}")
    void run() {
        reconciliationUseCase.runScheduled();
    }
}
