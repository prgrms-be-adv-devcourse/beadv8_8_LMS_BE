package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.coupon.app.CouponCapacitySupport;
import com.hapbang.coupon.app.CouponReconciliationMetrics;
import com.hapbang.coupon.config.CouponLimitsProperties;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.testsupport.RepositoryTest;

import io.micrometer.core.instrument.MeterRegistry;

// 용량 예약 합계의 조건부 UPSERT·UPDATE와 쿠폰별 예약 행(고아 해제의 시각 조건 포함)이 실제 DB에서 상한을 지키는지 본다
@RepositoryTest
class IssueCapacityRepositoryTest {

    private static final long LIMIT = 100;

    @Autowired
    private IssueCapacityRepository capacityRepository;

    @Autowired
    private IssueCapacityReservationRepository reservationRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 합계는_상한_안에서만_늘고_예약한_만큼만_줄며_음수가_되지_않는다() {
        assertThat(capacityRepository.reservedTotal()).isZero();

        assertThat(capacityRepository.reserve(60, LIMIT, NOW)).isEqualTo(1);   // 행이 없으면 만든다
        assertThat(capacityRepository.reserve(41, LIMIT, NOW)).isZero();       // 101 > 100
        assertThat(capacityRepository.reserve(40, LIMIT, NOW)).isEqualTo(1);   // 100
        assertThat(capacityRepository.reservedTotal()).isEqualTo(100);

        assertThat(capacityRepository.release(30, NOW)).isEqualTo(1);
        assertThat(capacityRepository.release(71, NOW)).isZero();
        assertThat(capacityRepository.reservedTotal()).isEqualTo(70);
    }

    @Test
    void 처음_예약도_상한을_넘으면_행을_만들지_않는다() {
        assertThat(capacityRepository.reserve(LIMIT + 1, LIMIT, NOW)).isZero();
        assertThat(capacityRepository.findAll()).isEmpty();
    }

    @Test
    void 쿠폰별_예약_행은_한_번만_만들고_한_번만_지운다() {
        long couponId = couponRepository.saveAndFlush(sellerCoupon()).getId();

        assertThat(reservationRepository.insertIfAbsent(couponId, 10, NOW)).isEqualTo(1);
        assertThat(reservationRepository.insertIfAbsent(couponId, 10, NOW)).isZero();
        assertThat(reservationRepository.findCouponIds()).containsExactly(couponId);

        assertThat(reservationRepository.deleteByCouponId(couponId)).isEqualTo(1);
        assertThat(reservationRepository.deleteByCouponId(couponId)).isZero();
    }

    @Test
    void 고아_예약_해제는_기준_시각_이전_예약만_지우고_열기_재시도가_시각을_바꾸면_지우지_않는다() {
        long couponId = couponRepository.saveAndFlush(sellerCoupon()).getId();
        reservationRepository.insertIfAbsent(couponId, 10, NOW);

        assertThat(reservationRepository.deleteByCouponIdReservedAtOrBefore(couponId, NOW.minusSeconds(1))).isZero();
        assertThat(reservationRepository.touch(couponId, NOW.plusSeconds(600))).isEqualTo(1); // 열기 재시도
        assertThat(reservationRepository.deleteByCouponIdReservedAtOrBefore(couponId, NOW.plusSeconds(599))).isZero();
        assertThat(reservationRepository.deleteByCouponIdReservedAtOrBefore(couponId, NOW.plusSeconds(600)))
                .isEqualTo(1);
        assertThat(reservationRepository.touch(couponId, NOW)).isZero();
    }

    @Test
    void 열기_재시도는_예약을_다시_쓰며_합계를_늘리지_않고_예약_시각만_바꾼다() {
        Coupon coupon = couponRepository.saveAndFlush(
                Coupon.createSeller(SELLER_ID, lectureRate().quantity(30).build(), NOW));
        Instant retryAt = NOW.plusSeconds(600);

        capacity(Clock.fixed(NOW, ZoneOffset.UTC)).reserve(coupon);
        capacity(Clock.fixed(retryAt, ZoneOffset.UTC)).reserve(coupon);

        assertThat(capacityRepository.reservedTotal()).isEqualTo(30);
        assertThat(reservationRepository.findById(coupon.getId()).orElseThrow().getReservedAt()).isEqualTo(retryAt);
    }

    // 여러 쿠폰을 동시에 열어도 커밋된 예약 합계가 상한을 넘지 않는다. 각 예약은 자기 트랜잭션으로 커밋한다
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 동시_발급_열기는_합산_상한까지만_예약하고_나머지는_409이며_예약_행을_남기지_않는다() throws Exception {
        int threads = 8;
        // 쿠폰 하나 30장, 상한 100장이면 셋만 들어간다
        List<Coupon> coupons = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            coupons.add(couponRepository.saveAndFlush(
                    Coupon.createSeller(SELLER_ID, lectureRate().quantity(30).build(), NOW)));
        }
        CouponCapacitySupport capacity = new CouponCapacitySupport(capacityRepository, reservationRepository,
                new CouponLimitsProperties(30, LIMIT, null, null, null), metrics(), transactionManager, Clock.systemUTC());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (Coupon coupon : coupons) {
                Callable<Boolean> reserve = () -> {
                    start.await();
                    try {
                        capacity.reserve(coupon);
                        return true;
                    } catch (CouponException exception) {
                        assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_CAPACITY_EXCEEDED);
                        return false;
                    }
                };
                results.add(executor.submit(reserve));
            }
            start.countDown();
            long reserved = 0;
            for (Future<Boolean> result : results) {
                reserved += result.get() ? 1 : 0;
            }

            assertThat(reserved).isEqualTo(3);
            assertThat(capacityRepository.reservedTotal()).isEqualTo(90);
            assertThat(reservationRepository.count()).isEqualTo(3);
        } finally {
            executor.shutdownNow();
        }
    }

    private CouponCapacitySupport capacity(Clock clock) {
        return new CouponCapacitySupport(capacityRepository, reservationRepository,
                new CouponLimitsProperties(30, LIMIT, null, null, null), metrics(), transactionManager, clock);
    }

    private static CouponReconciliationMetrics metrics() {
        return new CouponReconciliationMetrics(new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class));
    }
}
