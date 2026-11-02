package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.fixture.BillingFixture;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 보류·충전 원금 배분·충전 건의 반환 상태 전이와 금액 한도를 DB 없이 검증한다.
class PointHoldReleaseTest {

    private final Wallet wallet = Wallet.create(1L);

    @Test
    @DisplayName("일부를 반환하면 남은 보류액이 줄고 PARTIALLY_PROCESSED가 되며 반환 시각은 남기지 않는다")
    void partialRelease() {
        PointHold hold = hold(4_000L, 1_000L);

        hold.release(3_000L, 0L, false, BillingFixture.NOW);

        assertThat(hold.getHeldChargeHp()).isEqualTo(1_000L);
        assertThat(hold.getHeldRevenueHp()).isEqualTo(1_000L);
        assertThat(hold.heldTotalHp()).isEqualTo(2_000L);
        assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.PARTIALLY_PROCESSED);
        assertThat(hold.getReleasedAt()).isNull();
        assertThat(hold.isOpen()).isTrue();
        assertThat(hold.originalTotalHp()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("남은 보류를 모두 반환하면 RELEASED로 끝나고 반환 시각을 남긴다")
    void fullRelease() {
        PointHold hold = hold(4_000L, 1_000L);

        hold.release(4_000L, 1_000L, false, BillingFixture.NOW);

        assertThat(hold.heldTotalHp()).isZero();
        assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.RELEASED);
        assertThat(hold.getReleasedAt()).isEqualTo(BillingFixture.NOW);
        assertThat(hold.isOpen()).isFalse();
    }

    @Test
    @DisplayName("지급 이력이 있는 보류의 나머지를 모두 반환하면 MIXED로 끝난다")
    void fullReleaseAfterPayout() {
        PointHold hold = hold(4_000L, 0L);

        hold.release(4_000L, 0L, true, BillingFixture.NOW);

        assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.MIXED);
    }

    @Test
    @DisplayName("HP 종류별 남은 보류액을 넘는 반환은 RELEASE_AMOUNT_EXCEEDS_HELD로 거절하고 바꾸지 않는다")
    void exceeds() {
        PointHold hold = hold(4_000L, 1_000L);

        assertBillingError(() -> hold.release(4_001L, 0L, false, BillingFixture.NOW),
                BillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD);
        assertBillingError(() -> hold.release(0L, 1_001L, false, BillingFixture.NOW),
                BillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD);
        assertThat(hold.heldTotalHp()).isEqualTo(5_000L);
        assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.HELD);
    }

    @Test
    @DisplayName("이미 끝난 보류는 HOLD_ALREADY_CLOSED로 거절한다")
    void closed() {
        PointHold hold = hold(4_000L, 0L);
        hold.release(4_000L, 0L, false, BillingFixture.NOW);

        assertBillingError(() -> hold.release(1L, 0L, false, BillingFixture.NOW), BillingErrorCode.HOLD_ALREADY_CLOSED);
    }

    @Test
    @DisplayName("반환액이 0 이하이면 INVALID_POINT_AMOUNT로 거절한다")
    void invalidAmount() {
        PointHold hold = hold(4_000L, 1_000L);

        assertBillingError(() -> hold.release(0L, 0L, false, BillingFixture.NOW), BillingErrorCode.INVALID_POINT_AMOUNT);
        assertBillingError(() -> hold.release(-1L, 2L, false, BillingFixture.NOW), BillingErrorCode.INVALID_POINT_AMOUNT);
    }

    @Test
    @DisplayName("반환 상세는 보류의 참조 대상을 그대로 쓰고 RELEASE로 기록한다")
    void releasedDetail() {
        PointHold hold = hold(4_000L, 1_000L);

        PointHoldDetail detail = PointHoldDetail.released(hold, 3_000L, 500L, "RELEASE:1:R1");

        assertThat(detail.getPointHold()).isSameAs(hold);
        assertThat(detail.getReferenceType()).isEqualTo(hold.getReferenceType());
        assertThat(detail.getReferenceId()).isEqualTo(hold.getReferenceId());
        assertThat(detail.getActionType()).isEqualTo(HoldDetailActionType.RELEASE);
        assertThat(detail.totalAmountHp()).isEqualTo(3_500L);
    }

    @Test
    @DisplayName("배분의 일부를 반환하면 PARTIALLY_PROCESSED, 전부 반환하면 RELEASED가 되고 배분액은 보존된다")
    void usageRelease() {
        PointChargeUsage usage = PointChargeUsage.held(101L, hold(4_000L, 0L), 3_000L);

        usage.release(1_000L);
        assertThat(usage.getRemainingHeldHp()).isEqualTo(2_000L);
        assertThat(usage.getReleasedHp()).isEqualTo(1_000L);
        assertThat(usage.getStatus()).isEqualTo(PointChargeUsageStatus.PARTIALLY_PROCESSED);

        usage.release(2_000L);
        assertThat(usage.getRemainingHeldHp()).isZero();
        assertThat(usage.getReleasedHp()).isEqualTo(3_000L);
        assertThat(usage.getStatus()).isEqualTo(PointChargeUsageStatus.RELEASED);
        assertThat(usage.getRemainingHeldHp() + usage.getConsumedHp() + usage.getReleasedHp())
                .isEqualTo(usage.getAmountHp());
    }

    @Test
    @DisplayName("배분의 남은 보류보다 많이 반환하면 HOLD_STATE_INCONSISTENT로 거절한다")
    void usageReleaseExceeds() {
        PointChargeUsage usage = PointChargeUsage.held(101L, hold(4_000L, 0L), 3_000L);

        assertBillingError(() -> usage.release(3_001L), BillingErrorCode.HOLD_STATE_INCONSISTENT);
        assertBillingError(() -> usage.release(0L), BillingErrorCode.INVALID_POINT_AMOUNT);
        assertThat(usage.getRemainingHeldHp()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("배분 상태는 남은 보류와 소진·반환 이력으로 정한다")
    void usageStatus() {
        assertThat(PointChargeUsage.statusOf(1L, 0L, 0L)).isEqualTo(PointChargeUsageStatus.HELD);
        assertThat(PointChargeUsage.statusOf(1L, 1L, 0L)).isEqualTo(PointChargeUsageStatus.PARTIALLY_PROCESSED);
        assertThat(PointChargeUsage.statusOf(1L, 0L, 1L)).isEqualTo(PointChargeUsageStatus.PARTIALLY_PROCESSED);
        assertThat(PointChargeUsage.statusOf(0L, 0L, 1L)).isEqualTo(PointChargeUsageStatus.RELEASED);
        assertThat(PointChargeUsage.statusOf(0L, 1L, 0L)).isEqualTo(PointChargeUsageStatus.CONSUMED);
        assertThat(PointChargeUsage.statusOf(0L, 1L, 1L)).isEqualTo(PointChargeUsageStatus.MIXED);
    }

    @Test
    @DisplayName("반환받은 원금은 원래 충전 건의 가용 원금에 더하고, 충전 금액을 넘게 되면 CHARGE_PRINCIPAL_MISMATCH로 거절한다")
    void restorePrincipal() {
        PointCharge charge = BillingFixture.charge(wallet).amountHp(10_000L).remainingPrincipalHp(6_000L).build();

        charge.restorePrincipal(4_000L);
        assertThat(charge.getRemainingPrincipalHp()).isEqualTo(10_000L);

        assertBillingError(() -> charge.restorePrincipal(1L), BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        assertBillingError(() -> charge.restorePrincipal(0L), BillingErrorCode.INVALID_POINT_AMOUNT);
        assertThat(charge.getRemainingPrincipalHp()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("부분 환불 건에도 원금을 되돌리지만, 가용 원금으로 세지 않는 상태의 충전 건에는 되돌리지 않는다")
    void restorePrincipalByStatus() {
        PointCharge partiallyRefunded = BillingFixture.charge(wallet).remainingPrincipalHp(1_000L)
                .status(PointChargeStatus.PARTIALLY_REFUNDED).build();
        partiallyRefunded.restorePrincipal(1_000L);
        assertThat(partiallyRefunded.getRemainingPrincipalHp()).isEqualTo(2_000L);

        for (PointChargeStatus status : new PointChargeStatus[] {PointChargeStatus.PENDING, PointChargeStatus.FAILED,
                PointChargeStatus.REFUNDED}) {
            PointCharge charge = BillingFixture.charge(wallet).remainingPrincipalHp(0L).status(status).build();
            assertBillingError(() -> charge.restorePrincipal(1_000L), BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
    }

    private PointHold hold(long chargeHp, long revenueHp) {
        return PointHold.held(1L, BillingReferenceType.LECTURE_ORDER, 1L, chargeHp, revenueHp,
                "HOLD:LECTURE_ORDER:1", BillingFixture.NOW);
    }

    private static void assertBillingError(Runnable action, BillingErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
