package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

// 충전 HP 우선·수익 HP 부족분 반환 배분 계산을 DB 없이 검증한다.
class ReleaseSplitTest {

    @Test
    @DisplayName("남은 충전 HP 보류로 충분하면 충전 HP만 반환한다")
    void chargeOnly() {
        assertThat(ReleaseSplit.of(5_000L, 3_000L, 4_000L)).isEqualTo(new ReleaseSplit(4_000L, 0L));
    }

    @Test
    @DisplayName("남은 충전 HP 보류를 전부 먼저 반환하고 나머지를 수익 HP로 반환한다")
    void chargeFirstThenRevenue() {
        assertThat(ReleaseSplit.of(5_000L, 3_000L, 6_000L)).isEqualTo(new ReleaseSplit(5_000L, 1_000L));
    }

    @Test
    @DisplayName("남은 충전 HP 보류가 없으면 수익 HP만 반환한다")
    void revenueOnly() {
        assertThat(ReleaseSplit.of(0L, 3_000L, 2_000L)).isEqualTo(new ReleaseSplit(0L, 2_000L));
    }

    @Test
    @DisplayName("남은 보류 전부를 반환하는 금액은 허용한다")
    void exact() {
        assertThat(ReleaseSplit.of(5_000L, 3_000L, 8_000L)).isEqualTo(new ReleaseSplit(5_000L, 3_000L));
    }

    @Test
    @DisplayName("남은 보류 합계보다 많으면 RELEASE_AMOUNT_EXCEEDS_HELD로 거절한다")
    void exceeds() {
        assertThatThrownBy(() -> ReleaseSplit.of(5_000L, 3_000L, 8_001L))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD));
    }

    @Test
    @DisplayName("0 이하 금액은 INVALID_POINT_AMOUNT로 거절한다")
    void invalidAmount() {
        assertThatThrownBy(() -> ReleaseSplit.of(5_000L, 3_000L, 0L))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
    }
}
