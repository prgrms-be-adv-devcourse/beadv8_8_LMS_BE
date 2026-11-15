package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.coupon.config.CouponLimitsProperties;
import com.hapbang.coupon.domain.IssueBacklogRule.Decision;

class IssueBacklogRuleTest {

    private static final long THRESHOLD = 10;
    private static final long RELEASE_AT = 8;

    @Test
    void 재발행_대기가_임계치를_넘으면_보류한다() {
        assertThat(IssueBacklogRule.decide(11, THRESHOLD, RELEASE_AT, null)).isEqualTo(Decision.HOLD);
    }

    @Test
    void 임계치와_같으면_보류하지_않는다() {
        assertThat(IssueBacklogRule.decide(10, THRESHOLD, RELEASE_AT, null)).isEqualTo(Decision.KEEP);
    }

    @Test
    void 이미_적체로_보류_중이면_다시_쓰지_않는다() {
        assertThat(IssueBacklogRule.decide(11, THRESHOLD, RELEASE_AT, IssueHoldReason.BACKLOG)).isEqualTo(Decision.KEEP);
    }

    @ParameterizedTest
    @ValueSource(longs = {10, 9})
    void 적체_보류_중_대기가_임계치_이하여도_해제_기준보다_많으면_그대로_둔다(long waiting) {
        assertThat(IssueBacklogRule.decide(waiting, THRESHOLD, RELEASE_AT, IssueHoldReason.BACKLOG))
                .isEqualTo(Decision.KEEP);
    }

    @ParameterizedTest
    @ValueSource(longs = {8, 0})
    void 적체_보류_중_대기가_해제_기준_이하로_줄면_푼다(long waiting) {
        assertThat(IssueBacklogRule.decide(waiting, THRESHOLD, RELEASE_AT, IssueHoldReason.BACKLOG))
                .isEqualTo(Decision.RELEASE);
    }

    @ParameterizedTest
    @EnumSource(value = IssueHoldReason.class, names = {"RECONCILE", "RECOVERY"})
    void 다른_사유의_보류는_덮지도_풀지도_않는다(IssueHoldReason other) {
        assertThat(IssueBacklogRule.decide(11, THRESHOLD, RELEASE_AT, other)).isEqualTo(Decision.KEEP);
        assertThat(IssueBacklogRule.decide(0, THRESHOLD, RELEASE_AT, other)).isEqualTo(Decision.KEEP);
    }

    @Test
    void 해제_기준이_임계치보다_크면_거부한다() {
        assertThatThrownBy(() -> IssueBacklogRule.decide(0, THRESHOLD, THRESHOLD + 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 해제_기준은_임계치에_해제_비율을_곱해_내린다() {
        assertThat(CouponLimitsProperties.defaults().backlogReleaseAt()).isEqualTo(400);
        assertThat(new CouponLimitsProperties(null, null, 7, 0.5, null).backlogReleaseAt()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1})
    void 해제_비율은_0과_1_사이여야_한다(double ratio) {
        assertThatThrownBy(() -> new CouponLimitsProperties(null, null, null, ratio, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
