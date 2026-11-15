package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class IssueCapacityRuleTest {

    @ParameterizedTest
    @CsvSource({"99, true", "100, true", "101, false"})
    void 쿠폰_수량은_쿠폰별_상한까지만_받는다(long quantity, boolean expected) {
        assertThat(IssueCapacityRule.withinPerCoupon(quantity, 100)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0, 1000, true", "900, 100, true", "901, 100, false", "1000, 1, false"})
    void 예약_합계에_이번_수량을_더해_합산_상한까지만_받는다(long reservedTotal, long quantity, boolean expected) {
        assertThat(IssueCapacityRule.fitsTotal(reservedTotal, quantity, 1000)).isEqualTo(expected);
    }

    @Test
    void 발급_종료된_쿠폰도_Redis_기록이_남아_있으면_예약을_풀지_않는다() {
        assertThat(IssueCapacityRule.releasable(CouponStatus.CLOSED, 1, 0, 0)).isFalse();
        assertThat(IssueCapacityRule.releasable(CouponStatus.CLOSED, 0, 1, 0)).isFalse();
        assertThat(IssueCapacityRule.releasable(CouponStatus.CLOSED, 0, 0, 1)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = CouponStatus.class, names = {"CLOSED", "REJECTED"})
    void 다시_열_수_없는_쿠폰의_기록이_모두_정리되면_예약을_푼다(CouponStatus status) {
        assertThat(IssueCapacityRule.releasable(status, 0, 0, 0)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = CouponStatus.class, names = {"DRAFT", "REVIEW", "OPEN", "SUSPENDED"})
    void 다시_열_수_있거나_발급_중인_쿠폰은_기록이_없어도_예약을_풀지_않는다(CouponStatus status) {
        assertThat(IssueCapacityRule.releasable(status, 0, 0, 0)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = CouponStatus.class, names = {"DRAFT", "REVIEW", "CLOSED", "REJECTED"})
    void 발급_중이_아니고_Redis_key가_하나도_없으면_고아_예약이다(CouponStatus status) {
        assertThat(IssueCapacityRule.orphan(status, false)).isTrue();
        assertThat(IssueCapacityRule.orphan(status, true)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = CouponStatus.class, names = {"OPEN", "SUSPENDED"})
    void 발급_중인_쿠폰은_Redis_key가_없어도_고아가_아니다(CouponStatus status) {
        assertThat(IssueCapacityRule.orphan(status, false)).isFalse();
    }

    @Test
    void 열기_전_쿠폰의_고아_예약은_대기_시간이_지난_것만_풀고_다시_열_수_없는_쿠폰은_기다리지_않는다() {
        Instant now = Instant.parse("2026-11-08T05:00:00Z");
        Duration grace = Duration.ofMinutes(10);

        assertThat(IssueCapacityRule.orphanCutoff(CouponStatus.REVIEW, now, grace)).isEqualTo(now.minus(grace));
        assertThat(IssueCapacityRule.orphanCutoff(CouponStatus.DRAFT, now, grace)).isEqualTo(now.minus(grace));
        assertThat(IssueCapacityRule.orphanCutoff(CouponStatus.REJECTED, now, grace)).isNull();
        assertThat(IssueCapacityRule.orphanCutoff(CouponStatus.CLOSED, now, grace)).isNull();
    }
}
