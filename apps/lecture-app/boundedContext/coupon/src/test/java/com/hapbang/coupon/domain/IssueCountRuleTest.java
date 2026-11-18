package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IssueCountRuleTest {

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
