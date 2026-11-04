package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.fixture.BillingFixture;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 충전 건 가용 원금의 보류 배분(감소) 규칙을 DB 없이 검증한다.
class PointChargeTest {

    @Test
    @DisplayName("보류에 배분한 금액만큼 가용 원금이 줄고, 가용 원금 전부를 배분할 수 있다")
    void reservePrincipal() {
        PointCharge charge = BillingFixture.charge(Wallet.create(1L)).remainingPrincipalHp(6_000L).build();

        charge.reservePrincipal(2_000L);
        assertThat(charge.getRemainingPrincipalHp()).isEqualTo(4_000L);

        charge.reservePrincipal(4_000L);
        assertThat(charge.getRemainingPrincipalHp()).isZero();
    }

    @Test
    @DisplayName("가용 원금보다 많이 배분하면 CHARGE_PRINCIPAL_MISMATCH로 거절하고 원금을 바꾸지 않는다")
    void reserveMoreThanRemaining() {
        PointCharge charge = BillingFixture.charge(Wallet.create(1L)).remainingPrincipalHp(1_000L).build();

        assertThatThrownBy(() -> charge.reservePrincipal(1_001L))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH));
        assertThat(charge.getRemainingPrincipalHp()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("0 이하 금액은 INVALID_POINT_AMOUNT로 거절한다")
    void reserveInvalidAmount() {
        PointCharge charge = BillingFixture.charge(Wallet.create(1L)).build();

        assertThatThrownBy(() -> charge.reservePrincipal(0L))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
    }

    @Test
    @DisplayName("최초 배분 내역은 배분액 전부가 보류 중이고 소진·반환은 0이다")
    void usageHeld() {
        PointHold hold = PointHold.held(1L, BillingReferenceType.LECTURE_ORDER, 1L, 3_000L, 0L,
                "HOLD:LECTURE_ORDER:1", BillingFixture.NOW);

        PointChargeUsage usage = PointChargeUsage.held(101L, hold, 3_000L);

        assertThat(usage.getChargeId()).isEqualTo(101L);
        assertThat(usage.getPointHold()).isSameAs(hold);
        assertThat(usage.getAmountHp()).isEqualTo(3_000L);
        assertThat(usage.getRemainingHeldHp()).isEqualTo(3_000L);
        assertThat(usage.getConsumedHp()).isZero();
        assertThat(usage.getReleasedHp()).isZero();
        assertThat(usage.getStatus()).isEqualTo(PointChargeUsageStatus.HELD);
        assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.HELD);
        assertThat(hold.getHeldChargeHp()).isEqualTo(hold.getOriginalChargeHp());
        assertThat(hold.originalTotalHp()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("충전 주문은 승인 전(PENDING)이라 가용 원금 0이고, 승인 요청 시작 → 확정에서 충전 금액 전부가 가용 원금이 된다 (#224)")
    void chargeConfirmFlow() {
        Instant now = Instant.parse("2026-10-15T01:00:00Z");
        PointCharge charge = PointCharge.requested(1L, 10_000L, "HPCHG-order", now);

        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.PENDING);
        assertThat(charge.getRemainingPrincipalHp()).isZero();
        assertThat(charge.getPaymentAmountKrw()).isEqualTo(10_000L);
        assertThat(charge.isSettled()).isFalse();

        charge.startConfirm("v1:encrypted", "a".repeat(64));
        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.PROCESSING);
        assertThat(charge.hasPaymentKeyHash("a".repeat(64))).isTrue();
        assertThat(charge.hasPaymentKeyHash("b".repeat(64))).isFalse();
        assertThat(charge.getRemainingPrincipalHp()).isZero();

        charge.noteUnresolved("CONFIRM_NO_RESPONSE");
        assertThat(charge.getFailureReason()).isEqualTo("CONFIRM_NO_RESPONSE");

        charge.complete(now.plusSeconds(1));
        assertThat(charge.getStatus()).isEqualTo(PointChargeStatus.COMPLETED);
        assertThat(charge.getRemainingPrincipalHp()).isEqualTo(10_000L);
        assertThat(charge.getCompletedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(charge.getFailureReason()).isNull();
        assertThat(charge.isSettled()).isTrue();
    }

    @Test
    @DisplayName("승인 실패는 PROCESSING에서만 FAILED가 되고 원금은 0이다. 순서가 틀린 전이는 거절한다 (#224)")
    void chargeFailAndInvalidTransitions() {
        Instant now = Instant.parse("2026-10-15T01:00:00Z");
        PointCharge pending = PointCharge.requested(1L, 1_000L, "HPCHG-order", now);
        assertThatThrownBy(() -> pending.complete(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending.fail("x", now)).isInstanceOf(IllegalStateException.class);

        pending.startConfirm("v1:encrypted", "a".repeat(64));
        pending.fail("REJECT_CARD_PAYMENT: 한도초과", now);
        assertThat(pending.getStatus()).isEqualTo(PointChargeStatus.FAILED);
        assertThat(pending.getFailedAt()).isEqualTo(now);
        assertThat(pending.getRemainingPrincipalHp()).isZero();
        assertThatThrownBy(() -> pending.startConfirm("v1:other", "b".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending.complete(now)).isInstanceOf(IllegalStateException.class);
    }
}
