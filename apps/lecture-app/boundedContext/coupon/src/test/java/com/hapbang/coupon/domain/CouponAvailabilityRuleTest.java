package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class CouponAvailabilityRuleTest {

    private static final long OTHER_LECTURE_ID = 8L;
    private static final Instant IN_PERIOD = ISSUE_START.plusSeconds(1);

    private static Coupon withStatus(Coupon coupon, CouponStatus status) {
        ReflectionTestUtils.setField(coupon, "status", status);
        return coupon;
    }

    private static Coupon openLectureCoupon() {
        return withStatus(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW), CouponStatus.OPEN);
    }

    private static Coupon openAnyLectureCoupon() {
        return withStatus(Coupon.createPlatform(ADMIN_ID, anyLectureAmount().build(), NOW), CouponStatus.OPEN);
    }

    @Test
    void 발급_중이고_기간_안인_그_강의_대상_쿠폰은_목록에_넣고_다른_강의면_넣지_않는다() {
        Coupon coupon = openLectureCoupon();

        assertThat(CouponAvailabilityRule.isListedFor(coupon, LECTURE_ID, IN_PERIOD)).isTrue();
        assertThat(CouponAvailabilityRule.isListedFor(coupon, OTHER_LECTURE_ID, IN_PERIOD)).isFalse();
    }

    @Test
    void 모든_강의_대상_쿠폰은_어느_강의에나_넣는다() {
        Coupon coupon = openAnyLectureCoupon();

        assertThat(CouponAvailabilityRule.isListedFor(coupon, LECTURE_ID, IN_PERIOD)).isTrue();
        assertThat(CouponAvailabilityRule.isListedFor(coupon, OTHER_LECTURE_ID, IN_PERIOD)).isTrue();
    }

    @Test
    void 발급_기간은_시작_시각을_포함하고_종료_시각은_빼며_발급_접수와_같다() {
        Coupon coupon = openAnyLectureCoupon();

        assertThat(CouponAvailabilityRule.isListedFor(coupon, LECTURE_ID, ISSUE_START.minusMillis(1))).isFalse();
        assertThat(CouponAvailabilityRule.isListedFor(coupon, LECTURE_ID, ISSUE_START)).isTrue();
        assertThat(CouponAvailabilityRule.isListedFor(coupon, LECTURE_ID, ISSUE_END.minusMillis(1))).isTrue();
        assertThat(CouponAvailabilityRule.isListedFor(coupon, LECTURE_ID, ISSUE_END)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = CouponStatus.class, names = "OPEN", mode = EnumSource.Mode.EXCLUDE)
    void 발급_중이_아닌_쿠폰은_목록에_넣지_않는다(CouponStatus status) {
        Coupon coupon = withStatus(openAnyLectureCoupon(), status);

        assertThat(CouponAvailabilityRule.isListedFor(coupon, LECTURE_ID, IN_PERIOD)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"OPEN,true", "SUSPENDED,true", "CLOSED,true", "DRAFT,false", "REVIEW,false", "REJECTED,false"})
    void 회원은_발급을_연_적이_있는_쿠폰만_볼_수_있다(CouponStatus status, boolean visible) {
        assertThat(CouponAvailabilityRule.isVisibleToMember(status)).isEqualTo(visible);
    }

    @ParameterizedTest(name = "수량 {0}, 확정 {1}, Redis 재고 {2} → 매진 {3}")
    @CsvSource(nullValues = "null", value = {
            "10, 3, 7, false",
            "10, 3, 0, true",
            "10, 3, -1, true",
            // Redis가 먼저 줄었고 DB 확정은 아직이다
            "10, 9, 0, true",
            // Redis를 모르면(키 없음·장애) DB 확정 수만 본다
            "10, 9, null, false",
            "10, 10, null, true",
            // DB 확정 수가 수량에 닿았으면 Redis 값과 관계없이 매진이다
            "10, 10, 5, true"
    })
    void 매진은_Redis_재고가_0_이하이거나_확정_발급_수가_수량에_닿았을_때다(int quantity, int issuedCount, Long stock,
                                                       boolean soldOut) {
        assertThat(CouponAvailabilityRule.isSoldOut(quantity, issuedCount, stock)).isEqualTo(soldOut);
    }

    @ParameterizedTest
    @CsvSource({"true,false,true", "false,true,true", "true,true,true", "false,false,false"})
    void 받음은_발급_쿠폰이_있거나_Redis에_접수됐을_때다(boolean hasMemberCoupon, boolean accepted, boolean issued) {
        assertThat(CouponAvailabilityRule.isIssued(hasMemberCoupon, accepted)).isEqualTo(issued);
    }
}
