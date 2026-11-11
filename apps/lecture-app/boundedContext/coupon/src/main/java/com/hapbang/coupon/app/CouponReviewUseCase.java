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

/**
 * 판매자 쿠폰 심사 요청(DRAFT → REVIEW)과 거절(REVIEW → REJECTED). 승인은 발급을 여는 일이라 {@link CouponOpenUseCase}가 한다.
 * 둘 다 발급 저장소 용량을 예약하지 않는다(예약은 발급 열기만 한다).
 */
@Slf4j
@Service
public class CouponReviewUseCase {

    private final CouponSupport couponSupport;
    private final CouponCapacitySupport capacitySupport;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponReviewUseCase(CouponSupport couponSupport, CouponCapacitySupport capacitySupport,
                               PlatformTransactionManager transactionManager,
                               @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponSupport = couponSupport;
        this.capacitySupport = capacitySupport;
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

    public CouponView reject(long adminId, long couponId, String reason) {
        CouponView view = transaction.execute(status -> {
            Coupon coupon = couponSupport.findCoupon(couponId);
            coupon.reject(reason, clock.instant());
            return CouponView.from(coupon);
        });
        log.info("판매자 쿠폰 심사를 거절했다 couponId={}, adminId={}", couponId, adminId);
        releaseCapacityAfterReject(couponId);
        return view;
    }

    /**
     * 거절 커밋 뒤 용량 예약을 바로 푼다. 승인(발급 열기)이 중간에 실패해 예약이 남은 쿠폰이다. 거절되는 쿠폰은 접수를 연 적이 없어(접수는 OPEN
     * 커밋 뒤에만 연다) 정리할 요청 기록이 없다. 준비만 된 Redis key(issue-control·재고)는 만료 시각에 사라진다.
     * 풀지 못하면 거절은 그대로 두고 대사 배치가 기록을 정리하며 푼다.
     */
    private void releaseCapacityAfterReject(long couponId) {
        try {
            if (capacitySupport.release(couponId)) {
                log.info("거절한 판매자 쿠폰의 발급 저장소 용량 예약을 풀었다 couponId={}", couponId);
            }
        } catch (RuntimeException exception) {
            log.warn("거절한 판매자 쿠폰의 용량 예약을 풀지 못했다. 대사 배치가 푼다 couponId={}", couponId, exception);
        }
    }
}
