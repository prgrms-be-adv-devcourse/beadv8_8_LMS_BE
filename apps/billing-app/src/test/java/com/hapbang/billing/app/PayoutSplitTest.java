package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

// 지급 배분(수익 HP 우선)을 DB 없이 검증한다.
class PayoutSplitTest {

    @ParameterizedTest
    @CsvSource({
            "5000, 3000, 4000, 4000, 0",
            "3000, 5000, 4000, 3000, 1000",
            "0, 5000, 4000, 0, 4000",
            "3000, 5000, 8000, 3000, 5000"})
    @DisplayName("지급은 남은 수익 HP 보류를 먼저, 부족분만 충전 HP 보류에서 쓴다")
    void payoutSplit(long heldRevenue, long heldCharge, long amount, long revenue, long charge) {
        PayoutSplit split = PayoutSplit.of(heldRevenue, heldCharge, amount);

        assertThat(split).isEqualTo(new PayoutSplit(revenue, charge));
        assertThat(split.totalHp()).isEqualTo(amount);
    }

    @Test
    @DisplayName("남은 보류보다 많은 지급은 PAYOUT_AMOUNT_EXCEEDS_HELD, 0 이하는 INVALID_POINT_AMOUNT로 거절한다")
    void payoutSplitRejects() {
        assertThatThrownBy(() -> PayoutSplit.of(3_000L, 5_000L, 8_001L))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD));
        assertThatThrownBy(() -> PayoutSplit.of(3_000L, 5_000L, 0L))
                .isInstanceOfSatisfying(BillingException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(BillingErrorCode.INVALID_POINT_AMOUNT));
    }
}
