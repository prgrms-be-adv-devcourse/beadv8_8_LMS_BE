package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.billing.domain.SettlementStatus;
import com.hapbang.shared.billing.domain.BillingHoldStatus;
import com.hapbang.shared.billing.domain.BillingSettlementStatus;

// 보류·지급 조회 응답(#577)은 지갑 Enum을 같은 이름의 shared Enum으로 바꾼다(BillingResultDtos). 상수가 어긋나면 응답 중 실패하므로 여기서 고정한다.
class BillingStatusContractTest {

    @Test
    @DisplayName("shared BillingHoldStatus는 지갑 PointHoldStatus와 상수가 같다")
    void holdStatus() {
        assertThat(names(BillingHoldStatus.values())).containsExactly(names(PointHoldStatus.values()));
    }

    @Test
    @DisplayName("shared BillingSettlementStatus는 지갑 SettlementStatus와 상수가 같다")
    void settlementStatus() {
        assertThat(names(BillingSettlementStatus.values())).containsExactly(names(SettlementStatus.values()));
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
}
