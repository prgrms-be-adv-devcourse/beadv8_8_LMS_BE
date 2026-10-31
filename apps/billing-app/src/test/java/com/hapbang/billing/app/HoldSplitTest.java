package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

// 수익 HP 우선·충전 HP 부족분 배분 계산을 DB 없이 검증한다.
class HoldSplitTest {

    @Test
    @DisplayName("수익 HP가 충분하면 수익 HP만 쓰고 충전 HP는 쓰지 않는다")
    void revenueOnly() {
        assertThat(HoldSplit.of(5_000L, 10_000L, 4_000L)).isEqualTo(new HoldSplit(4_000L, 0L));
    }

    @Test
    @DisplayName("수익 HP가 부족하면 수익 HP를 전부 먼저 쓰고 나머지를 충전 HP로 채운다")
    void revenueFirstThenCharge() {
        assertThat(HoldSplit.of(3_000L, 10_000L, 8_000L)).isEqualTo(new HoldSplit(3_000L, 5_000L));
    }

    @Test
    @DisplayName("수익 HP가 없으면 충전 HP만 쓴다")
    void chargeOnly() {
        assertThat(HoldSplit.of(0L, 10_000L, 8_000L)).isEqualTo(new HoldSplit(0L, 8_000L));
    }

    @Test
    @DisplayName("가용 수익 HP와 충전 HP를 정확히 다 쓰는 금액은 허용한다")
    void exactBalance() {
        assertThat(HoldSplit.of(3_000L, 2_000L, 5_000L)).isEqualTo(new HoldSplit(3_000L, 2_000L));
    }

    @Test
    @DisplayName("가용 수익 HP와 충전 HP를 합쳐도 모자라면 INSUFFICIENT_BALANCE로 거절한다")
    void insufficient() {
        assertThatThrownBy(() -> HoldSplit.of(3_000L, 2_000L, 5_001L))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));
    }

    @Test
    @DisplayName("0 이하 금액은 INVALID_POINT_AMOUNT로 거절한다")
    void invalidAmount() {
        assertThatThrownBy(() -> HoldSplit.of(1_000L, 1_000L, 0L))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
    }
}
