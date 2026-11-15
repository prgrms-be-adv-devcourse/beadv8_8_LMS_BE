package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponCreateTest.assertError;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BudgetWorkTest {

    private static final Instant LATER = NOW.plusSeconds(60);

    @Test
    void 예약_작업_ID는_쿠폰과_정책_버전에서_정해지고_재시도는_시도_번호를_붙인다() {
        assertThat(BudgetWork.reserveWorkIdOf(42L, 1, 1)).isEqualTo("coupon-42-reserve-v1");
        assertThat(BudgetWork.reserveWorkIdOf(42L, 1, 2)).isEqualTo("coupon-42-reserve-v1-a2");
        assertThat(BudgetWork.reserveWorkIdOf(42L, 1, 3)).isEqualTo("coupon-42-reserve-v1-a3");
        assertThat(BudgetWork.reserveWorkIdOf(42L, 2, 1)).isNotEqualTo(BudgetWork.reserveWorkIdOf(42L, 1, 1));
        assertThatThrownBy(() -> BudgetWork.reserveWorkIdOf(42L, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 플랫폼_쿠폰의_예약_작업은_쿠폰_예산액으로_대기_상태로_만든다() {
        Coupon coupon = savedPlatformCoupon(42L);

        BudgetWork work = BudgetWork.reserve(coupon, BudgetWork.FIRST_ATTEMPT, NOW);
        BudgetWork retry = BudgetWork.reserve(coupon, 2, NOW);

        assertThat(work.getWorkId()).isEqualTo("coupon-42-reserve-v1").isEqualTo(coupon.budgetReserveWorkId());
        assertThat(retry.getWorkId()).isEqualTo("coupon-42-reserve-v1-a2").isEqualTo(coupon.budgetReserveWorkId(2));
        assertThat(work.getCouponId()).isEqualTo(42L);
        assertThat(work.getKind()).isEqualTo(BudgetWorkKind.RESERVE);
        assertThat(work.getAmount()).isEqualTo(coupon.budgetAmount());
        assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.PENDING);
        assertThat(work.isNew()).isTrue();
    }

    @Test
    void 판매자_쿠폰은_예약_작업을_만들지_않는다() {
        Coupon coupon = sellerCoupon();
        ReflectionTestUtils.setField(coupon, "id", 42L);

        assertError(() -> BudgetWork.reserve(coupon, BudgetWork.FIRST_ATTEMPT, NOW), CouponDomainError.BUDGET_NOT_APPLICABLE);
    }

    @Test
    void 성공은_예약_ID를_결과로_남기고_같은_결과는_다시_기록해도_같다() {
        BudgetWork work = BudgetWork.reserve(savedPlatformCoupon(42L), BudgetWork.FIRST_ATTEMPT, NOW);

        work.markDone("reservation-1", LATER);
        work.markDone("reservation-1", LATER.plusSeconds(1));

        assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.DONE);
        assertThat(work.getResult()).isEqualTo("reservation-1");
        assertThat(work.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void 끝난_작업의_결과는_바꾸지_않는다() {
        BudgetWork done = BudgetWork.reserve(savedPlatformCoupon(42L), BudgetWork.FIRST_ATTEMPT, NOW);
        done.markDone("reservation-1", LATER);
        BudgetWork failed = BudgetWork.reserve(savedPlatformCoupon(43L), BudgetWork.FIRST_ATTEMPT, NOW);
        failed.markFailed("예산 부족", LATER);

        assertError(() -> done.markDone("reservation-2", LATER), CouponDomainError.INVALID_BUDGET_WORK_TRANSITION);
        assertError(() -> done.markFailed("예산 부족", LATER), CouponDomainError.INVALID_BUDGET_WORK_TRANSITION);
        assertError(() -> failed.markDone("reservation-1", LATER), CouponDomainError.INVALID_BUDGET_WORK_TRANSITION);
        assertThat(failed.getStatus()).isEqualTo(BudgetWorkStatus.FAILED);
        assertThat(failed.getResult()).isEqualTo("예산 부족");
    }

    @Test
    void 해제_작업은_예약과_대상_금액을_보존하고_콜론_없는_결정적_ID를_쓴다() {
        Coupon coupon = savedPlatformCoupon(42L);
        coupon.recordBudgetReserved("PB-7", NOW);
        BudgetWork work = BudgetWork.release(coupon, "member-12-to-1000", 700, "MEMBER_COUPON:12", "만료", NOW);
        assertThat(work.getWorkId()).isEqualTo("coupon-42-release-member-12-to-1000").doesNotContain(":");
        assertThat(work.getReservationId()).isEqualTo("PB-7");
        assertThat(work.getTarget()).isEqualTo("MEMBER_COUPON:12");
        assertThat(work.getAmount()).isEqualTo(700);
        assertThat(work.getKind()).isEqualTo(BudgetWorkKind.RELEASE);
        assertThatThrownBy(() -> BudgetWork.release(coupon, "bad:id", 1, "MEMBER_COUPON:12", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetWork.release(coupon, "x", 0, "MEMBER_COUPON:12", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetWork.release(savedPlatformCoupon(43L), "x", 1, "MEMBER_COUPON:12", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Coupon savedPlatformCoupon(long id) {
        Coupon coupon = platformCoupon();
        ReflectionTestUtils.setField(coupon, "id", id);
        return coupon;
    }
}
