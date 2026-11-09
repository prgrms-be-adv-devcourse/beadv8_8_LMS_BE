package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IssueCountRuleTest {

    @ParameterizedTest(name = "확정 {0}장 / 총 {1}장 → {2}")
    @CsvSource({"0, 1, true", "9, 10, true", "10, 10, false", "11, 10, false"})
    void 확정_발급_수가_총수량보다_작을_때만_한_장_더_확정한다(int issuedCount, int quantity, boolean expected) {
        assertThat(IssueCountRule.canIncrement(issuedCount, quantity)).isEqualTo(expected);
    }

    @Test
    void 조건부_UPDATE가_1행이면_확정이고_0행이면_총수량_초과다() {
        assertThat(IssueCountRule.interpret(1)).isEqualTo(IssueCountRule.Outcome.INCREMENTED);
        assertThat(IssueCountRule.interpret(0)).isEqualTo(IssueCountRule.Outcome.OVER_QUANTITY);
    }

    @Test
    void 쿠폰_한_행이_아닌_행_수는_있을_수_없어_실패한다() {
        assertThatThrownBy(() -> IssueCountRule.interpret(2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IssueCountRule.interpret(-1)).isInstanceOf(IllegalStateException.class);
    }
}
