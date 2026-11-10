package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// 쿠폰 예산 예약의 금액 나눔(총액 = 남은 예산 + 보류 중 + 지급됨 + 해제됨)과 해제 규칙을 DB 없이 검증한다.
class BudgetReservationTest {

    @Test
    @DisplayName("예약하면 총액 전부가 남은 예산이고 보류 중·지급됨·해제됨은 0이다")
    void reserve() {
        BudgetReservation reservation = BudgetReservation.reserve("coupon-42-reserve-v1", 2L, 42L, 1, 100_000L);

        assertThat(reservation.getWorkId()).isEqualTo("coupon-42-reserve-v1");
        assertThat(reservation.getWalletId()).isEqualTo(2L);
        assertThat(reservation.getCouponId()).isEqualTo(42L);
        assertThat(reservation.getPolicyVersion()).isEqualTo(1);
        assertThat(reservation.getAmountHp()).isEqualTo(100_000L);
        assertThat(reservation.getRemainingHp()).isEqualTo(100_000L);
        assertThat(reservation.getHeldHp()).isZero();
        assertThat(reservation.getPaidHp()).isZero();
        assertThat(reservation.getReleasedHp()).isZero();
        assertThat(reservation.isBalanced()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("예약 금액이 0 이하면 INVALID_POINT_AMOUNT로 거절한다")
    void reserveInvalidAmount(long amountHp) {
        assertThatThrownBy(() -> BudgetReservation.reserve("w", 2L, 42L, 1, amountHp))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
    }

    @Test
    @DisplayName("정책 버전이 1보다 작거나 필수 값이 없으면 예약하지 않는다")
    void reserveInvalidValues() {
        assertThatThrownBy(() -> BudgetReservation.reserve("w", 2L, 42L, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetReservation.reserve(null, 2L, 42L, 1, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BudgetReservation.reserve("w", null, 42L, 1, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BudgetReservation.reserve("w", 2L, null, 1, 1L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("해제는 남은 예산을 줄이고 해제됨을 늘려 총액 나눔을 지킨다. 남은 예산 전부까지 해제할 수 있다")
    void release() {
        BudgetReservation reservation = BudgetReservation.reserve("w", 2L, 42L, 1, 100_000L);

        reservation.release(30_000L);
        assertThat(reservation.getRemainingHp()).isEqualTo(70_000L);
        assertThat(reservation.getReleasedHp()).isEqualTo(30_000L);
        assertThat(reservation.isBalanced()).isTrue();

        reservation.release(70_000L);
        assertThat(reservation.getRemainingHp()).isZero();
        assertThat(reservation.getReleasedHp()).isEqualTo(100_000L);
        assertThat(reservation.getAmountHp()).isEqualTo(100_000L);
        assertThat(reservation.isBalanced()).isTrue();
    }

    @Test
    @DisplayName("남은 예산보다 많이 해제하면 거절하고 예약을 바꾸지 않는다")
    void releaseExceedsRemaining() {
        BudgetReservation reservation = BudgetReservation.reserve("w", 2L, 42L, 1, 100_000L);
        reservation.release(60_000L);

        assertThatThrownBy(() -> reservation.release(40_001L)).isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getRemainingHp()).isEqualTo(40_000L);
        assertThat(reservation.getReleasedHp()).isEqualTo(60_000L);
        assertThat(reservation.isBalanced()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("해제 금액이 0 이하면 INVALID_POINT_AMOUNT로 거절하고 예약을 바꾸지 않는다")
    void releaseInvalidAmount(long amountHp) {
        BudgetReservation reservation = BudgetReservation.reserve("w", 2L, 42L, 1, 100L);

        assertThatThrownBy(() -> reservation.release(amountHp))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
        assertThat(reservation.getRemainingHp()).isEqualTo(100L);
        assertThat(reservation.getReleasedHp()).isZero();
    }

    @Test
    @DisplayName("충전·회수·해제 기록은 1 HP 이상이고 필수 값이 있어야 만든다")
    void workRecords() {
        assertThat(PromotionFunding.of("PF-1", 2L, 1L, PromotionFundingSourceType.ADMIN_GRANT, "결재-1", null, 9L)
                .getAmountHp()).isEqualTo(1L);
        assertThat(PromotionWithdrawal.of("PW-1", 2L, 1L, "결재-2", null, 9L).getAmountHp()).isEqualTo(1L);
        assertThat(BudgetRelease.of("coupon-42-release-v1", 7L, 1L, "UNISSUED:30", null).getTarget())
                .isEqualTo("UNISSUED:30");

        for (long amountHp : new long[] {0L, -1L}) {
            assertThatThrownBy(() -> PromotionFunding.of("PF-1", 2L, amountHp, PromotionFundingSourceType.ADMIN_GRANT,
                    "결재-1", null, 9L)).isInstanceOf(BillingException.class);
            assertThatThrownBy(() -> PromotionWithdrawal.of("PW-1", 2L, amountHp, "결재-2", null, 9L))
                    .isInstanceOf(BillingException.class);
            assertThatThrownBy(() -> BudgetRelease.of("r", 7L, amountHp, "ADMIN", null))
                    .isInstanceOf(BillingException.class);
        }
        assertThatThrownBy(() -> PromotionFunding.of("PF-1", 2L, 1L, PromotionFundingSourceType.ADMIN_GRANT, null,
                null, 9L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PromotionWithdrawal.of("PW-1", 2L, 1L, null, null, 9L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BudgetRelease.of("r", 7L, 1L, null, null)).isInstanceOf(NullPointerException.class);
    }
}
