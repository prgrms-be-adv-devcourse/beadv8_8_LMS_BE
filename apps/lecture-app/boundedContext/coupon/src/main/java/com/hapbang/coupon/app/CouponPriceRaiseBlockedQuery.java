package com.hapbang.coupon.app;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;

/**
 * 가격 인상 제한 조회(강의 → 쿠폰 내부 API). 강의가 가격을 올리기 전에 부른다. 그 강의 대상 플랫폼 쿠폰이 발급 중(OPEN)이거나, 그 쿠폰의
 * 미사용 발급 쿠폰(RESERVED 전부와 만료 전 AVAILABLE)이 있으면 막는다. 판매자 쿠폰은 보지 않는다.
 */
@Service
public class CouponPriceRaiseBlockedQuery {

    private final CouponRepository couponRepository;
    private final Clock clock;

    public CouponPriceRaiseBlockedQuery(CouponRepository couponRepository,
                                        @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponRepository = couponRepository;
        this.clock = clock;
    }

    public CouponPriceRaiseBlockedResponse blocked(long lectureId) {
        return new CouponPriceRaiseBlockedResponse(lectureId,
                couponRepository.existsPriceRaiseBlocker(lectureId, clock.instant()));
    }
}
