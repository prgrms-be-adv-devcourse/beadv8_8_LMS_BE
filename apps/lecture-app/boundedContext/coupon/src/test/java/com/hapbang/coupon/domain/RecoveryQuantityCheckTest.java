package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecoveryQuantityCheckTest {

    @ParameterizedTest(name = "총수량 {0} = DB {1} + 재고 {2} + 진행 중 {3} → {4}")
    @CsvSource(nullValues = "null", value = {
            "10, 2, 8, 0, true",
            "10, 10, 0, 0, true",
            "10, 2, 7, 0, false",
            "10, 2, 7, 1, false",
            "10, 2, 9, 0, false",
            "10, 2, null, 0, false",
            "10, 12, -2, 0, false"
    })
    void 총수량이_DB_발급과_재고의_합이고_진행_중이_없을_때만_맞다(int quantity, int dbIssued, Long stock, long inProgress,
                                                    boolean expected) {
        assertThat(new RecoveryQuantityCheck(quantity, dbIssued, stock, inProgress).matches()).isEqualTo(expected);
    }
}
