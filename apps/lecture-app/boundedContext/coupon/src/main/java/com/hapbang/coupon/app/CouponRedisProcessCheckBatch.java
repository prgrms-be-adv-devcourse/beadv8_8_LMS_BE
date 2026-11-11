package com.hapbang.coupon.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * Redis 프로세스 변경 점검 배치. 간격은 {@code hapbang.coupon.recovery.process-check-interval}(기본 1분, 측정·합의 전 값)이고 처리는
 * {@link CouponRedisProcessCheckUseCase}가 한다.
 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class CouponRedisProcessCheckBatch {

    private final CouponRedisProcessCheckUseCase couponRedisProcessCheckUseCase;

    @Scheduled(fixedDelayString = "${hapbang.coupon.recovery.process-check-interval:PT1M}")
    void run() {
        couponRedisProcessCheckUseCase.checkAll();
    }
}
