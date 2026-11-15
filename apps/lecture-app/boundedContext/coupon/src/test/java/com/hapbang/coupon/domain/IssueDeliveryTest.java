package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IssueDeliveryTest {

    private static final long COUPON_ID = 42L;
    private static final Instant ISSUED_AT = Instant.parse("2026-11-08T01:02:03.456Z");
    private static final Instant EXPIRES_AT = ISSUED_AT.plus(Duration.ofDays(30));

    private static IssueDelivery delivery(String requestId, long memberId, long epoch) {
        return new IssueDelivery(requestId, COUPON_ID, memberId, epoch, ISSUED_AT, EXPIRES_AT);
    }

    @Test
    void 작업_ID는_쿠폰_ID와_요청_ID로_만들어_재발행된_같은_요청은_같은_값이다() {
        assertThat(delivery("req-1", MEMBER_ID, 1).operationId()).isEqualTo("coupon-issue:42:req-1");
        assertThat(delivery("req-1", MEMBER_ID, 1).operationId()).isEqualTo(delivery("req-1", MEMBER_ID, 1).operationId());
        // 요청 ID는 쿠폰 안에서만 유일하다: 다른 쿠폰의 같은 요청 ID는 다른 작업이다
        assertThat(new IssueDelivery("req-1", 43L, MEMBER_ID, 1, ISSUED_AT, EXPIRES_AT).operationId())
                .isNotEqualTo(delivery("req-1", MEMBER_ID, 1).operationId());
    }

    @Test
    void 내용_식별값은_회원_epoch_접수_시각_만료_시각이고_하나라도_다르면_다르다() {
        IssueDelivery base = delivery("req-1", MEMBER_ID, 1);

        assertThat(base.contentKey())
                .isEqualTo(MEMBER_ID + "|1|" + ISSUED_AT.toEpochMilli() + "|" + EXPIRES_AT.toEpochMilli());
        assertThat(delivery("req-1", OTHER_MEMBER_ID, 1).contentKey()).isNotEqualTo(base.contentKey());
        assertThat(delivery("req-1", MEMBER_ID, 2).contentKey()).isNotEqualTo(base.contentKey());
        assertThat(new IssueDelivery("req-1", COUPON_ID, MEMBER_ID, 1, ISSUED_AT, EXPIRES_AT.plusMillis(1)).contentKey())
                .isNotEqualTo(base.contentKey());
    }

    @ParameterizedTest(name = "요청 epoch {0}, 쿠폰 epoch {1} → {2}")
    @CsvSource({"2, 2, CURRENT", "1, 2, STALE", "3, 2, AHEAD"})
    void epoch는_쿠폰의_지금_epoch와_같을_때만_확정_대상이다(long requestEpoch, long couponEpoch, IssueEpochRule.Verdict expected) {
        assertThat(delivery("req-1", MEMBER_ID, requestEpoch).checkEpoch(couponEpoch)).isEqualTo(expected);
    }

    @Test
    void 기존_결과가_같은_회원_epoch_접수_시각이면_같은_요청이고_하나라도_다르면_다른_내용이다() {
        IssueDelivery delivery = delivery("req-1", MEMBER_ID, 1);
        Instant processedAt = NOW;

        assertThat(delivery.sameRequestAs(delivery.toRejected("사유", processedAt))).isTrue();
        assertThat(delivery.sameRequestAs(delivery("req-1", OTHER_MEMBER_ID, 1).toRejected("사유", processedAt))).isFalse();
        assertThat(delivery.sameRequestAs(delivery("req-1", MEMBER_ID, 2).toRejected("사유", processedAt))).isFalse();
        assertThat(delivery.sameRequestAs(CouponIssueRequest.rejected("req-1", MEMBER_ID, COUPON_ID, 1,
                ISSUED_AT.plusMillis(1), "사유", processedAt))).isFalse();
    }

    @Test
    void 발급_쿠폰은_최초_접수_시각과_이벤트의_만료_시각을_그대로_쓴다() {
        MemberCoupon memberCoupon = delivery("req-1", MEMBER_ID, 1).toMemberCoupon(NOW);

        assertThat(memberCoupon.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(memberCoupon.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(memberCoupon.getIssueRequestId()).isEqualTo("req-1");
        assertThat(memberCoupon.getCouponId()).isEqualTo(COUPON_ID);
    }

    @Test
    void 비었거나_형식이_틀린_요청은_만들지_않는다() {
        assertThatThrownBy(() -> delivery(" ", MEMBER_ID, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery("r".repeat(101), MEMBER_ID, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery("req-1", 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery("req-1", MEMBER_ID, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssueDelivery("req-1", 0L, MEMBER_ID, 1, ISSUED_AT, EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssueDelivery("req-1", COUPON_ID, MEMBER_ID, 1, null, EXPIRES_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IssueDelivery("req-1", COUPON_ID, MEMBER_ID, 1, ISSUED_AT, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
