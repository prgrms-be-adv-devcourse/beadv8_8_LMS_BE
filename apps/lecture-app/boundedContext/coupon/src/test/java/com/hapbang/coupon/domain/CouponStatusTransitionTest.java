package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponCreateTest.assertError;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CouponStatusTransitionTest {

    private static final Instant LATER = NOW.plusSeconds(60);
    private static final String RESERVATION_ID = "reservation-1";

    enum Action {
        SUBMIT_REVIEW((c, now) -> c.submitReview(now)),
        REJECT((c, now) -> c.reject("할인율이 너무 큽니다", now)),
        OPEN(Coupon::open),
        SUSPEND(Coupon::suspend),
        RESUME(Coupon::resume),
        CLOSE(Coupon::close);

        private final BiConsumer<Coupon, Instant> call;

        Action(BiConsumer<Coupon, Instant> call) {
            this.call = call;
        }
    }

    /** 유형·상태별로 허용하는 전이와 다음 상태. 여기 없는 (상태, 동작)은 모두 거절이다 */
    private static final Map<CouponType, Map<CouponStatus, Map<Action, CouponStatus>>> ALLOWED = Map.of(
            CouponType.SELLER, Map.of(
                    CouponStatus.DRAFT, Map.of(Action.SUBMIT_REVIEW, CouponStatus.REVIEW),
                    CouponStatus.REVIEW, Map.of(Action.OPEN, CouponStatus.OPEN, Action.REJECT, CouponStatus.REJECTED),
                    CouponStatus.REJECTED, Map.of(),
                    CouponStatus.OPEN, Map.of(Action.SUSPEND, CouponStatus.SUSPENDED, Action.CLOSE, CouponStatus.CLOSED),
                    CouponStatus.SUSPENDED, Map.of(Action.RESUME, CouponStatus.OPEN, Action.CLOSE, CouponStatus.CLOSED),
                    CouponStatus.CLOSED, Map.of()),
            CouponType.PLATFORM, Map.of(
                    CouponStatus.DRAFT, Map.of(Action.OPEN, CouponStatus.OPEN),
                    CouponStatus.OPEN, Map.of(Action.SUSPEND, CouponStatus.SUSPENDED, Action.CLOSE, CouponStatus.CLOSED),
                    CouponStatus.SUSPENDED, Map.of(Action.RESUME, CouponStatus.OPEN, Action.CLOSE, CouponStatus.CLOSED),
                    CouponStatus.CLOSED, Map.of()));

    static Stream<Arguments> transitions() {
        List<Arguments> cases = new ArrayList<>();
        ALLOWED.forEach((type, byStatus) -> byStatus.forEach((status, allowed) -> {
            for (Action action : Action.values()) {
                cases.add(Arguments.of(type, status, action, allowed.get(action)));
            }
        }));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1} --{2}--> {3}")
    @MethodSource("transitions")
    void 상태_전이는_정해진_것만_허용한다(CouponType type, CouponStatus from, Action action, CouponStatus expected) {
        Coupon coupon = preparedCouponIn(type, from);

        if (expected == null) {
            assertError(() -> action.call.accept(coupon, LATER), CouponDomainError.INVALID_STATUS_TRANSITION);
            assertThat(coupon.getStatus()).isEqualTo(from);
        } else {
            action.call.accept(coupon, LATER);
            assertThat(coupon.getStatus()).isEqualTo(expected);
            assertThat(coupon.getUpdatedAt()).isEqualTo(LATER);
        }
    }

    @Test
    void 판매자_쿠폰은_Redis_준비_전에는_승인해도_열리지_않는다() {
        Coupon coupon = sellerCoupon();
        coupon.submitReview(NOW);

        assertError(() -> coupon.open(LATER), CouponDomainError.REDIS_NOT_READY);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.REVIEW);
    }

    @Test
    void 플랫폼_쿠폰은_예산_확보_전에는_열리지_않는다() {
        Coupon coupon = platformCoupon();

        assertError(() -> coupon.open(LATER), CouponDomainError.BUDGET_NOT_RESERVED);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.DRAFT);
    }

    @Test
    void 플랫폼_쿠폰은_예산을_확보해도_Redis_준비_전에는_열리지_않는다() {
        Coupon coupon = platformCoupon();
        coupon.recordBudgetReserved(RESERVATION_ID, NOW);

        assertError(() -> coupon.open(LATER), CouponDomainError.REDIS_NOT_READY);
    }

    @Test
    void 플랫폼_쿠폰은_예산보다_Redis_준비를_먼저_기록할_수_없다() {
        Coupon coupon = platformCoupon();

        assertError(() -> coupon.markRedisReady(NOW), CouponDomainError.BUDGET_NOT_RESERVED);
        assertThat(coupon.isRedisReady()).isFalse();
    }

    @Test
    void 판매자_쿠폰은_심사_전에_Redis_준비를_기록할_수_없다() {
        assertError(() -> sellerCoupon().markRedisReady(NOW), CouponDomainError.INVALID_STATUS_TRANSITION);
    }

    @Test
    void Redis_준비_기록은_다시_불러도_같다() {
        Coupon coupon = sellerCoupon();
        coupon.submitReview(NOW);
        coupon.markRedisReady(NOW);

        coupon.markRedisReady(LATER);

        assertThat(coupon.isRedisReady()).isTrue();
        assertThat(coupon.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void 예산_예약은_같은_ID로_다시_기록해도_같고_다른_ID면_거절한다() {
        Coupon coupon = platformCoupon();
        coupon.recordBudgetReserved(RESERVATION_ID, NOW);

        coupon.recordBudgetReserved(RESERVATION_ID, LATER);

        assertThat(coupon.getBudgetReservationId()).isEqualTo(RESERVATION_ID);
        assertError(() -> coupon.recordBudgetReserved("reservation-2", LATER),
                CouponDomainError.BUDGET_RESERVATION_MISMATCH);
    }

    @Test
    void 판매자_쿠폰에는_예산_예약을_기록하지_않는다() {
        assertError(() -> sellerCoupon().recordBudgetReserved(RESERVATION_ID, NOW),
                CouponDomainError.INVALID_STATUS_TRANSITION);
    }

    @Test
    void 열린_뒤에는_새_예산_예약을_기록하지_않지만_같은_예약_ID_재기록은_무변경_성공이다() {
        Coupon coupon = preparedCouponIn(CouponType.PLATFORM, CouponStatus.OPEN);
        String recorded = coupon.getBudgetReservationId();

        assertError(() -> coupon.recordBudgetReserved("reservation-2", LATER),
                CouponDomainError.INVALID_STATUS_TRANSITION);
        if (recorded != null) {
            // 같은 예산 작업을 기다린 다른 열기 요청이 먼저 열었어도 뒤 요청의 같은 결과 기록은 성공한다
            coupon.recordBudgetReserved(recorded, LATER);
            assertThat(coupon.getBudgetReservationId()).isEqualTo(recorded);
        }
    }

    @Test
    void 거절은_사유를_남긴다() {
        Coupon coupon = sellerCoupon();
        coupon.submitReview(NOW);

        coupon.reject("강의 가격 대비 할인이 큽니다", LATER);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.REJECTED);
        assertThat(coupon.getRejectReason()).isEqualTo("강의 가격 대비 할인이 큽니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void 거절_사유가_비면_거절하지_않는다(String reason) {
        Coupon coupon = sellerCoupon();
        coupon.submitReview(NOW);

        assertError(() -> coupon.reject(reason, LATER), CouponDomainError.REJECT_REASON_REQUIRED);
        assertError(() -> coupon.reject(null, LATER), CouponDomainError.REJECT_REASON_REQUIRED);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.REVIEW);
    }

    /** 정상 경로로 그 상태까지 만든다. 열 수 있는 상태면 열기 준비(예산·Redis)도 끝내 둬, 거절 이유가 상태뿐이게 한다 */
    private static Coupon preparedCouponIn(CouponType type, CouponStatus status) {
        Coupon coupon = type == CouponType.SELLER ? sellerCoupon() : platformCoupon();
        if (type == CouponType.PLATFORM) {
            coupon.recordBudgetReserved(RESERVATION_ID, NOW);
            coupon.markRedisReady(NOW);
        }
        if (status == CouponStatus.DRAFT) {
            return coupon;
        }
        if (type == CouponType.SELLER) {
            coupon.submitReview(NOW);
            if (status == CouponStatus.REJECTED) {
                coupon.reject("사유", NOW);
                return coupon;
            }
            coupon.markRedisReady(NOW);
            if (status == CouponStatus.REVIEW) {
                return coupon;
            }
        }
        coupon.open(NOW);
        if (status == CouponStatus.SUSPENDED) {
            coupon.suspend(NOW);
        } else if (status == CouponStatus.CLOSED) {
            coupon.close(NOW);
        }
        assertThat(coupon.getStatus()).isEqualTo(status);
        return coupon;
    }
}
