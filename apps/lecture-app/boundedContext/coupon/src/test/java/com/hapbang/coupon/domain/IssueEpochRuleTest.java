package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.hapbang.coupon.domain.IssueEpochRule.Verdict;

class IssueEpochRuleTest {

    @ParameterizedTest(name = "요청 epoch {0}, 쿠폰 epoch {1} → {2}")
    @CsvSource({
            "1, 1, CURRENT",
            "1, 2, STALE",
            "2, 5, STALE",
            "3, 2, AHEAD"
    })
    void 요청_epoch를_쿠폰의_지금_epoch와_비교한다(long requestEpoch, long couponEpoch, Verdict expected) {
        assertThat(IssueEpochRule.judge(requestEpoch, couponEpoch)).isEqualTo(expected);
    }

    @Test
    void 다음_epoch는_하나_큰_값이고_첫_epoch보다_작은_값은_받지_않는다() {
        assertThat(IssueEpochRule.next(Coupon.INITIAL_EPOCH)).isEqualTo(2L);
        assertThatThrownBy(() -> IssueEpochRule.next(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IssueEpochRule.next(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
    }
}
