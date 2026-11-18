package com.hapbang.coupon.in.scheduler;

import com.hapbang.coupon.app.CouponBudgetWorkUseCase;
import com.hapbang.coupon.global.scheduling.ConditionalOnSchedulingEnabled;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 주기·lease·묶음 크기는 측정 전 기본값이며 설정으로 조정한다. */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class CouponBudgetWorkBatch {
    private final CouponBudgetWorkUseCase useCase;

    @Scheduled(fixedDelayString = "${hapbang.coupon.budget-work.interval:PT1M}",
            initialDelayString = "${hapbang.coupon.budget-work.interval:PT1M}")
    void run() {
        useCase.runDue();
    }
}
