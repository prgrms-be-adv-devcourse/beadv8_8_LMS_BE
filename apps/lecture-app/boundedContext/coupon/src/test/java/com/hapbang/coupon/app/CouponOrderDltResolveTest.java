package com.hapbang.coupon.app;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.config.CouponDltProperties;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.UsageItem;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 재처리가 소비를 커밋한 뒤 REPLAYED를 남기기 전에 멈춰 REPLAYING(lease 지남)으로 남은 주문 이벤트 DLT 기록을 실제 DB로 닫아 본다.
 * 이미 반영된 이벤트라 영구 거절은 409이고 기록은 그대로이며, 재처리는 소비 멱등으로 아무것도 바꾸지 않고 REPLAYED로 끝난다.
 */
@IntegrationTest
@Import(RedisTestcontainersConfig.class)
class CouponOrderDltResolveTest {

    private static final long ORDER_ID = 9501L;
    private static final long ORDER_ITEM_ID = 95011L;
    private static final long PRICE = 10_000L;

    @Autowired
    private CouponOrderConsumerUseCase couponOrderConsumerUseCase;

    @Autowired
    private CouponOrderDltRejectUseCase couponOrderDltRejectUseCase;

    @Autowired
    private CouponOrderDltReplayUseCase couponOrderDltReplayUseCase;

    @Autowired
    private CouponDltLogRepository couponDltLogRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private CouponUsageRepository couponUsageRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 이미_반영된_이벤트의_DLT_기록은_영구_거절하지_않고_재처리하면_멱등으로_REPLAYED가_된다() {
        Instant now = Instant.now();
        long memberCouponId = reservedUsage(now);
        OrderItemPaidEvent paid = OrderItemPaidEvent.of(ORDER_ID, ORDER_ITEM_ID, MEMBER_ID,
                List.of(new OrderItemPaidEvent.Usage(memberCouponId, 1)), now);
        assertThat(couponOrderConsumerUseCase.consume(paid)).isEqualTo(CouponOrderConsumerUseCase.Result.APPLIED);
        Duration lease = CouponDltProperties.defaults().dltReplayLease();
        CouponDltLog stale = CouponDltLog.isolateOrderEvent(new CouponDltLog.Source("lecture.events.v1", 0, 77L,
                "order-item:" + ORDER_ITEM_ID), CouponDltPayload.writeOrderEvent(paid), "원인", now);
        stale.claimReplay(ADMIN_ID, now.minus(lease).minusSeconds(1), lease);
        long dltId = couponDltLogRepository.save(stale).getId();

        assertThatThrownBy(() -> couponOrderDltRejectUseCase.reject(ADMIN_ID, dltId, "반영 안 된 줄 알고 거절"))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        assertThat(couponDltLogRepository.findById(dltId).orElseThrow()).satisfies(log -> {
            assertThat(log.getStatus()).isEqualTo(CouponDltStatus.REPLAYING);
            assertThat(log.getRejectReason()).isNull();
        });

        CouponDltLogView replayed = couponOrderDltReplayUseCase.replay(ADMIN_ID, dltId);

        assertThat(replayed.status()).isEqualTo(CouponDltStatus.REPLAYED);
        assertThat(couponUsageRepository.findByMemberCouponIdAndGeneration(memberCouponId, 1).orElseThrow()
                .getStatus()).isEqualTo(CouponUsageStatus.PAID);
    }

    /** 판매자 쿠폰 한 장을 주문 항목에 예약한 상태(발급 쿠폰 RESERVED·generation 1, 사용 시도 RESERVED). 발급 쿠폰 ID */
    private long reservedUsage(Instant now) {
        Long couponId = couponRepository.save(Coupon.createSeller(SELLER_ID, lectureRate().build(), now)).getId();
        MemberCoupon memberCoupon = memberCouponRepository.save(MemberCoupon.issue(MEMBER_ID, couponId, "req-resolve",
                now, now.plus(Duration.ofDays(30)), now));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                memberCouponRepository.reserve(memberCoupon.getId(), MEMBER_ID, 0, ORDER_ITEM_ID, now));
        couponUsageRepository.save(CouponUsage.reserve(MEMBER_ID, ORDER_ID,
                new UsageItem(ORDER_ITEM_ID, LECTURE_ID, SELLER_ID, PRICE),
                new AppliedCoupon(memberCoupon.getId(), couponId, CouponType.SELLER, 1, PRICE, 1_000, 0, 1_000, 0, 1),
                "lecture-order:" + ORDER_ID + ":reserve", now));
        return memberCoupon.getId();
    }
}
