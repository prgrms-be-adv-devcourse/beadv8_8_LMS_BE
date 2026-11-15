package com.hapbang.coupon.domain.usage;

import static com.hapbang.coupon.domain.usage.UsageFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import com.hapbang.coupon.domain.CouponDomainError;
import com.hapbang.coupon.domain.MemberCouponStatus;

// 사용 가능·적용 대상·중첩 규칙과 선택 매핑(selections) 계산을 본다
class CouponStackingRuleTest {

    private static final long LECTURE_A = 1L;
    private static final long LECTURE_B = 2L;

    private final CouponCalculator calculator = new CouponCalculator();

    private ItemResult apply(UsableCoupon... coupons) {
        return calculator.apply(item(LECTURE_A, 10_000), List.of(coupons), MEMBER_ID, NOW);
    }

    private CalculationResult select(List<UsableCoupon> owned, Selection... selections) {
        return calculator.calculate(List.of(item(LECTURE_A, 10_000), item(LECTURE_B, 20_000)), owned,
                List.of(selections), MEMBER_ID, NOW, MAX_NODES);
    }

    private static void assertViolation(ThrowingCallable call, CouponDomainError error, long memberCouponId) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(CouponUsageViolationException.class, exception -> {
                    assertThat(exception.getError()).isEqualTo(error);
                    assertThat(exception.getMemberCouponId()).isEqualTo(memberCouponId);
                });
    }

    // 중첩 ---------------------------------------------------------------------------------------------------

    @Test
    void 한_항목에_판매자_쿠폰_1장과_플랫폼_쿠폰_1장을_함께_쓸_수_있다() {
        ItemResult result = apply(seller(1, LECTURE_A).build(), platformAny(2).build());

        assertThat(result.coupons()).extracting(AppliedCoupon::memberCouponId).containsExactly(1L, 2L);
    }

    @Test
    void 같은_주체의_쿠폰_두_장은_거절한다() {
        assertViolation(() -> apply(seller(1, LECTURE_A).build(), seller(2, LECTURE_A).build()),
                CouponDomainError.COUPON_STACKING_VIOLATION, 2);
        assertViolation(() -> apply(platformLecture(3, LECTURE_A).build(), platformAny(4).build()),
                CouponDomainError.COUPON_STACKING_VIOLATION, 4);
    }

    @Test
    void 단독_사용_쿠폰은_다른_쿠폰과_함께_둘_수_없고_혼자는_쓸_수_있다() {
        assertViolation(() -> apply(seller(1, LECTURE_A).stackable(false).build(), platformAny(2).build()),
                CouponDomainError.COUPON_STACKING_VIOLATION, 1);
        assertViolation(() -> apply(seller(1, LECTURE_A).build(), platformAny(2).stackable(false).build()),
                CouponDomainError.COUPON_STACKING_VIOLATION, 2);

        assertThat(apply(seller(1, LECTURE_A).stackable(false).build()).discount()).isEqualTo(1_000);
    }

    @Test
    void 한_발급_쿠폰은_한_항목에만_둔다() {
        UsableCoupon any = platformAny(1).build();

        assertViolation(() -> select(List.of(any), new Selection(1, LECTURE_A), new Selection(1, LECTURE_B)),
                CouponDomainError.COUPON_STACKING_VIOLATION, 1);
        assertViolation(() -> apply(any, any), CouponDomainError.COUPON_STACKING_VIOLATION, 1);
    }

    // 적용 대상 ------------------------------------------------------------------------------------------------

    @Test
    void 특정_강의_쿠폰은_그_강의에만_모든_강의_쿠폰은_어느_강의에나_적용한다() {
        assertViolation(() -> apply(seller(1, LECTURE_B).build()), CouponDomainError.COUPON_NOT_APPLICABLE, 1);
        assertViolation(() -> apply(platformLecture(2, LECTURE_B).build()), CouponDomainError.COUPON_NOT_APPLICABLE, 2);

        assertThat(apply(platformAny(3).build()).discount()).isEqualTo(1_000);
    }

    @Test
    void 주문에_없는_강의에_둔_쿠폰은_적용_대상이_아니다() {
        assertViolation(() -> select(List.of(platformAny(1).build()), new Selection(1, 99L)),
                CouponDomainError.COUPON_NOT_APPLICABLE, 1);
    }

    // 사용 가능 ------------------------------------------------------------------------------------------------

    @Test
    void 만료_시각과_지금이_같으면_쓸_수_없고_그_직전까지_쓸_수_있다() {
        assertViolation(() -> apply(platformAny(1).expiresAt(NOW).build()), CouponDomainError.COUPON_NOT_USABLE, 1);

        assertThat(apply(platformAny(1).expiresAt(NOW.plusNanos(1_000)).build()).discount()).isEqualTo(1_000);
    }

    @Test
    void 남의_쿠폰과_AVAILABLE이_아닌_쿠폰은_쓸_수_없다() {
        assertViolation(() -> apply(platformAny(1).owner(OTHER_MEMBER_ID).build()),
                CouponDomainError.COUPON_NOT_USABLE, 1);
        assertViolation(() -> apply(platformAny(2).status(MemberCouponStatus.RESERVED).build()),
                CouponDomainError.COUPON_NOT_USABLE, 2);
        assertViolation(() -> apply(platformAny(3).status(MemberCouponStatus.USED).build()),
                CouponDomainError.COUPON_NOT_USABLE, 3);
    }

    @Test
    void 사용_가능_검사가_적용_대상_검사보다_먼저다() {
        assertViolation(() -> apply(seller(1, LECTURE_B).owner(OTHER_MEMBER_ID).build()),
                CouponDomainError.COUPON_NOT_USABLE, 1);
    }

    @Test
    void 회원의_쿠폰_목록에_없는_쿠폰을_고르면_쓸_수_없다() {
        assertViolation(() -> select(List.of(), new Selection(5, LECTURE_A)), CouponDomainError.COUPON_NOT_USABLE, 5);
    }

    // 선택 매핑 ------------------------------------------------------------------------------------------------

    @Test
    void 빈_매핑은_쿠폰을_쓰지_않는다() {
        CalculationResult result = calculator.calculate(List.of(item(LECTURE_A, 10_000)),
                List.of(platformAny(1).build()), List.of(), MEMBER_ID, NOW, MAX_NODES);

        assertThat(result.recommended()).isFalse();
        assertThat(result.optimal()).isFalse();
        assertThat(result.totalDiscount()).isZero();
        assertThat(result.totalBuyerAmount()).isEqualTo(10_000);
        assertThat(result.items().getFirst().coupons()).isEmpty();
    }

    @Test
    void 매핑에_없는_강의는_추천으로_채우지_않는다() {
        List<UsableCoupon> owned = List.of(platformAny(1).build(), platformAny(2).build());

        CalculationResult result = select(owned, new Selection(1, LECTURE_A));

        assertThat(result.recommended()).isFalse();
        assertThat(result.items()).extracting(ItemResult::discount).containsExactly(1_000L, 0L);
        assertThat(result.totalDiscount()).isEqualTo(1_000);
        assertThat(result.totalBuyerAmount()).isEqualTo(29_000);
    }

    @Test
    void 직접_고른_0_할인_쿠폰은_거절하지_않고_d_0으로_적용한다() {
        // 판매자 정액이 남은 금액을 모두 깎아 플랫폼 정률의 할인은 0이다
        List<UsableCoupon> owned = List.of(seller(1, LECTURE_A).amount(10_000).build(), platformAny(2).build());

        CalculationResult result = select(owned, new Selection(1, LECTURE_A), new Selection(2, LECTURE_A));

        ItemResult itemA = result.items().getFirst();
        assertThat(itemA.coupons()).extracting(AppliedCoupon::memberCouponId, AppliedCoupon::discount)
                .containsExactly(tuple(1L, 10_000L),
                        tuple(2L, 0L));
        assertThat(itemA.buyerAmount()).isZero();
    }

    @Test
    void 한_주문에_같은_강의가_두_번_있으면_계산하지_않는다() {
        assertThatThrownBy(() -> calculator.calculate(List.of(item(LECTURE_A, 1_000), item(LECTURE_A, 1_000)),
                List.of(), null, MEMBER_ID, NOW, MAX_NODES))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
