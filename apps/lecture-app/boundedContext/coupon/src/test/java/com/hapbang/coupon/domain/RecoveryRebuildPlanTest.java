package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecoveryRebuildPlanTest {

    private static final Instant ACCEPTED = Instant.parse("2026-11-06T04:00:00.123Z");

    private static Coupon couponWithIssued(int quantity, int issued) {
        Coupon coupon = Coupon.createPlatform(ADMIN_ID, anyLectureAmount().quantity(quantity).build(), NOW);
        ReflectionTestUtils.setField(coupon, "issuedCount", issued);
        return coupon;
    }

    private static RecoveryIssueResult issued(String requestId, long memberId) {
        return new RecoveryIssueResult(requestId, memberId, CouponIssueResult.ISSUED, ACCEPTED, 1L);
    }

    @Test
    void 재고는_총수량에서_확정_발급_수를_뺀_값이고_회원은_발급_확정_회원이다() {
        Coupon coupon = couponWithIssued(10, 2);
        List<RecoveryIssueResult> results = List.of(issued("req-a", MEMBER_ID), issued("req-b", OTHER_MEMBER_ID),
                new RecoveryIssueResult("req-c", 300L, CouponIssueResult.REJECTED, ACCEPTED, 1L));

        RecoveryRebuildPlan plan = RecoveryRebuildPlan.of(coupon, 2L, results);

        assertThat(plan.newEpoch()).isEqualTo(2L);
        assertThat(plan.stock()).isEqualTo(8L);
        assertThat(plan.memberIds()).containsExactly(MEMBER_ID, OTHER_MEMBER_ID);
        assertThat(plan.requests()).hasSize(3);
    }

    @Test
    void 요청_결과는_DB_epoch를_실은_접수_기록_형식이고_만료_시각은_최초_접수_시각으로_다시_계산한다() {
        Coupon coupon = couponWithIssued(10, 1);
        long expiresAt = ACCEPTED.plus(Duration.ofDays(30)).toEpochMilli();

        RecoveryRebuildPlan plan = RecoveryRebuildPlan.of(coupon, 2L, List.of(issued("req-a", MEMBER_ID),
                new RecoveryIssueResult("req-c", 300L, CouponIssueResult.REJECTED, ACCEPTED, 1L)));

        // 접수 epoch는 새 epoch(2)가 아니라 요청이 처리된 DB epoch(1)다.
        // 발급 확정은 전송 표식만, 거절은 보상 표식까지 붙어 영구 거절 보상이 재고를 다시 늘리지 않는다
        assertThat(plan.requests()).containsEntry("req-a",
                MEMBER_ID + "|" + ACCEPTED.toEpochMilli() + "|ACCEPTED|" + expiresAt + "|e1|PUBLISHED");
        assertThat(plan.requests()).containsEntry("req-c",
                "300|" + ACCEPTED.toEpochMilli() + "|ACCEPTED|" + expiresAt + "|e1|PUBLISHED|COMPENSATED");
        assertThat(IssueRequestRecord.parse(plan.requests().get("req-c")).compensated()).isTrue();
        assertThat(plan.memberIds()).containsExactly(MEMBER_ID);
    }

    @Test
    void DB_epoch를_읽어_오지_않은_결과는_접수_epoch_없이_쓴다() {
        RecoveryRebuildPlan plan = RecoveryRebuildPlan.of(couponWithIssued(10, 1), 2L,
                List.of(new RecoveryIssueResult("req-a", MEMBER_ID, CouponIssueResult.ISSUED, ACCEPTED)));

        assertThat(IssueRequestRecord.parse(plan.requests().get("req-a")).epoch()).isNull();
    }

    @Test
    void 고정_만료_쿠폰은_고정_만료_시각을_쓴다() {
        Instant fixed = ISSUE_END.plus(Duration.ofDays(10));
        Coupon coupon = Coupon.createPlatform(ADMIN_ID, anyLectureAmount().quantity(5).fixed(fixed).build(), NOW);
        ReflectionTestUtils.setField(coupon, "issuedCount", 1);

        RecoveryRebuildPlan plan = RecoveryRebuildPlan.of(coupon, 2L, List.of(issued("req-a", MEMBER_ID)));

        assertThat(IssueRequestRecord.parse(plan.requests().get("req-a")).expiresAt()).isEqualTo(fixed);
    }

    @Test
    void 결과가_없으면_전체_수량이_재고이고_비어_있다() {
        RecoveryRebuildPlan plan = RecoveryRebuildPlan.of(couponWithIssued(10, 0), 2L, List.of());

        assertThat(plan.stock()).isEqualTo(10L);
        assertThat(plan.memberIds()).isEmpty();
        assertThat(plan.requests()).isEmpty();
    }

    @Test
    void 확정_발급_수가_발급_확정_결과_수와_다르면_만들지_않는다() {
        Coupon coupon = couponWithIssued(10, 2);

        assertThatThrownBy(() -> RecoveryRebuildPlan.of(coupon, 2L, List.of(issued("req-a", MEMBER_ID))))
                .isInstanceOf(CouponDomainException.class)
                .hasMessageContaining("DB 발급 수");
    }

    @Test
    void 한_회원의_발급_확정_결과가_둘이면_만들지_않는다() {
        Coupon coupon = couponWithIssued(10, 2);

        assertThatThrownBy(() -> RecoveryRebuildPlan.of(coupon, 2L,
                List.of(issued("req-a", MEMBER_ID), issued("req-b", MEMBER_ID))))
                .isInstanceOf(CouponDomainException.class);
    }
}
