package com.hapbang.coupon.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 발급 쿠폰 만료 배치. 앞 회차가 끝난 뒤 {@code hapbang.coupon.expiry.interval}(기본 10분, 측정·합의 전 값)마다 실행한다.
 * 처리는 {@link MemberCouponExpiryUseCase}가 한다
 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class MemberCouponExpiryBatch {

    private final MemberCouponExpiryUseCase memberCouponExpiryUseCase;

    @Scheduled(fixedDelayString = "${hapbang.coupon.expiry.interval:PT10M}",
            initialDelayString = "${hapbang.coupon.expiry.interval:PT10M}")
    void run() {
        memberCouponExpiryUseCase.expireDue();
    }
}
