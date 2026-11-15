package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IssueHoldReasonTest {

    @Test
    void 무거운_사유만_가벼운_사유를_덮고_같은_사유는_다시_쓰지_않는다() {
        assertThat(IssueHoldReason.BACKLOG.canOverride(null)).isTrue();
        assertThat(IssueHoldReason.RECONCILE.canOverride(IssueHoldReason.BACKLOG)).isTrue();
        assertThat(IssueHoldReason.RECOVERY.canOverride(IssueHoldReason.RECONCILE)).isTrue();
        assertThat(IssueHoldReason.BACKLOG.canOverride(IssueHoldReason.RECONCILE)).isFalse();
        assertThat(IssueHoldReason.RECONCILE.canOverride(IssueHoldReason.RECOVERY)).isFalse();
        assertThat(IssueHoldReason.RECONCILE.canOverride(IssueHoldReason.RECONCILE)).isFalse();
        assertThat(IssueHoldReason.RECOVERY.lighter())
                .containsExactly(IssueHoldReason.BACKLOG, IssueHoldReason.RECONCILE);
    }

    @Test
    void 비어_있으면_보류_없음이고_모르는_값은_가장_무거운_사유로_읽어_덮거나_풀지_않는다() {
        assertThat(IssueHoldReason.parse(null)).isNull();
        assertThat(IssueHoldReason.parse("")).isNull();
        assertThat(IssueHoldReason.parse("BACKLOG")).isEqualTo(IssueHoldReason.BACKLOG);
        assertThat(IssueHoldReason.parse("MANUAL")).isEqualTo(IssueHoldReason.RECOVERY);
    }
}
