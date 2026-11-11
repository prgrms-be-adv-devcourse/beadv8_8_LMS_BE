package com.hapbang.coupon.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 발급 요청 재발행 배치. 앞 회차가 끝난 뒤 {@code hapbang.coupon.republish.interval}(기본 1분, 측정·합의 전 값)마다 실행한다.
 * 처리는 {@link CouponRepublishUseCase}가 한다
 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class CouponRepublishBatch {

    private final CouponRepublishUseCase couponRepublishUseCase;

    @Scheduled(fixedDelayString = "${hapbang.coupon.republish.interval:PT1M}",
            initialDelayString = "${hapbang.coupon.republish.interval:PT1M}")
    void run() {
        couponRepublishUseCase.republishDue();
    }
}
