package com.hapbang.coupon.app;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.Coupon;

import lombok.extern.slf4j.Slf4j;

/** 판매자 쿠폰 심사 요청(DRAFT → REVIEW)과 거절(REVIEW → REJECTED). 승인은 발급을 여는 일이라 {@link CouponOpenUseCase}가 한다 */
@Slf4j
@Service
public class CouponReviewUseCase {

    private final CouponSupport couponSupport;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponReviewUseCase(CouponSupport couponSupport, PlatformTransactionManager transactionManager,
                               @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponSupport = couponSupport;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 신청한 판매자 본인만 심사에 올린다 */
    public CouponView submitReview(long sellerId, long couponId) {
        CouponView view = transaction.execute(status -> {
            Coupon coupon = couponSupport.findCoupon(couponId);
            couponSupport.requireOwner(coupon, sellerId);
            coupon.submitReview(clock.instant());
            return CouponView.from(coupon);
        });
        log.info("판매자 쿠폰을 심사에 올렸다 couponId={}, sellerId={}", couponId, sellerId);
        return view;
    }

    public CouponView reject(long couponId, String reason) {
        CouponView view = transaction.execute(status -> {
            Coupon coupon = couponSupport.findCoupon(couponId);
            coupon.reject(reason, clock.instant());
            return CouponView.from(coupon);
        });
        log.info("판매자 쿠폰 심사를 거절했다 couponId={}", couponId);
        return view;
    }
}
