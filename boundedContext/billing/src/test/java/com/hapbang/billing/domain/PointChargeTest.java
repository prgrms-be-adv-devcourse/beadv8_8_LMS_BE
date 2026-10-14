package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.fixture.BillingFixture;

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
}
