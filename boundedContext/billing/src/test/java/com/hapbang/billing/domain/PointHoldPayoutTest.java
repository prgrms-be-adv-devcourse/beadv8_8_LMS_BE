package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.fixture.BillingFixture;

// 보류·충전 원금 배분의 지급 상태 전이와 금액 한도, 지급 상세·지급 건 생성을 DB 없이 검증한다.
class PointHoldPayoutTest {

    @Test
    @DisplayName("일부를 지급하면 남은 보류액이 줄고 PARTIALLY_PROCESSED가 된다")
    void partialPayout() {
        PointHold hold = hold(4_000L, 1_000L);

        hold.payout(1_000L, 1_000L, false);

        assertThat(hold.getHeldChargeHp()).isEqualTo(3_000L);
        assertThat(hold.getHeldRevenueHp()).isZero();
        assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.PARTIALLY_PROCESSED);
        assertThat(hold.isOpen()).isTrue();
    }

    @Test
    @DisplayName("반환 이력 없이 지급으로만 남은 보류를 모두 쓰면 PAID, 반환 이력이 있으면 MIXED로 끝나고 반환 시각은 남기지 않는다")
    void closingPayout() {
        PointHold paid = hold(4_000L, 1_000L);
        paid.payout(4_000L, 1_000L, false);
        assertThat(paid.getStatus()).isEqualTo(PointHoldStatus.PAID);
        assertThat(paid.getReleasedAt()).isNull();
        assertThat(paid.isOpen()).isFalse();

        PointHold mixed = hold(4_000L, 0L);
        mixed.release(1_000L, 0L, false, BillingFixture.NOW);
        mixed.payout(3_000L, 0L, true);
        assertThat(mixed.getStatus()).isEqualTo(PointHoldStatus.MIXED);
    }

    @Test
    @DisplayName("HP 종류별 남은 보류액을 넘는 지급은 PAYOUT_AMOUNT_EXCEEDS_HELD, 끝난 보류는 HOLD_ALREADY_CLOSED로 거절한다")
    void rejects() {
        PointHold hold = hold(4_000L, 1_000L);

        assertBillingError(() -> hold.payout(4_001L, 0L, false), BillingErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD);
        assertBillingError(() -> hold.payout(0L, 1_001L, false), BillingErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD);
        assertBillingError(() -> hold.payout(0L, 0L, false), BillingErrorCode.INVALID_POINT_AMOUNT);
        assertThat(hold.heldTotalHp()).isEqualTo(5_000L);

        hold.payout(4_000L, 1_000L, false);
        assertBillingError(() -> hold.payout(1L, 0L, false), BillingErrorCode.HOLD_ALREADY_CLOSED);
        assertBillingError(() -> hold.release(1L, 0L, false, BillingFixture.NOW), BillingErrorCode.HOLD_ALREADY_CLOSED);
    }

    @Test
    @DisplayName("배분을 지급으로 소진하면 소진액이 늘고, 남은 보류가 0이면 CONSUMED(반환 없음)·MIXED(반환 있음)가 된다")
    void usageConsume() {
        PointChargeUsage consumed = PointChargeUsage.held(101L, hold(3_000L, 0L), 3_000L);
        consumed.consume(1_000L);
        assertThat(consumed.getStatus()).isEqualTo(PointChargeUsageStatus.PARTIALLY_PROCESSED);
        consumed.consume(2_000L);
        assertThat(consumed.getConsumedHp()).isEqualTo(3_000L);
        assertThat(consumed.getStatus()).isEqualTo(PointChargeUsageStatus.CONSUMED);

        PointChargeUsage mixed = PointChargeUsage.held(101L, hold(3_000L, 0L), 3_000L);
        mixed.release(1_000L);
        mixed.consume(2_000L);
        assertThat(mixed.getStatus()).isEqualTo(PointChargeUsageStatus.MIXED);
        assertThat(mixed.getRemainingHeldHp() + mixed.getConsumedHp() + mixed.getReleasedHp())
                .isEqualTo(mixed.getAmountHp());

        assertBillingError(() -> mixed.consume(1L), BillingErrorCode.HOLD_STATE_INCONSISTENT);
        assertBillingError(() -> consumed.consume(0L), BillingErrorCode.INVALID_POINT_AMOUNT);
    }

    @Test
    @DisplayName("지급 상세는 보류의 참조 대상을 쓰고 PAYOUT으로 기록하며, 바로 지급한 지급 건은 총액 = 완료액, COMPLETED다")
    void detailAndSettlement() {
        PointHold hold = hold(4_000L, 1_000L);

        PointHoldDetail detail = PointHoldDetail.paidOut(hold, 500L, 1_000L, "PAYOUT:1:P1");
        Settlement settlement = Settlement.completed(2L, 20L, 1L, SettlementSourceType.APPEARANCE_REQUEST, 1L,
                PayoutType.NORMAL, 1_500L, "PAYOUT:1:P1", BillingFixture.NOW);

        assertThat(detail.getActionType()).isEqualTo(HoldDetailActionType.PAYOUT);
        assertThat(detail.getReferenceType()).isEqualTo(hold.getReferenceType());
        assertThat(detail.totalAmountHp()).isEqualTo(1_500L);
        assertThat(settlement.getGrossAmountHp()).isEqualTo(1_500L);
        assertThat(settlement.getPaidAmountHp()).isEqualTo(1_500L);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(settlement.getScheduledAt()).isEqualTo(BillingFixture.NOW);
        assertThat(settlement.getSettledAt()).isEqualTo(BillingFixture.NOW);
    }

    private static PointHold hold(long chargeHp, long revenueHp) {
        return PointHold.held(1L, BillingReferenceType.APPEARANCE_REQUEST, 1L, chargeHp, revenueHp,
                "HOLD:APPEARANCE_REQUEST:1", BillingFixture.NOW);
    }

    private static void assertBillingError(Runnable action, BillingErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
