package com.hapbang.coupon.domain.usage;

import static com.hapbang.coupon.domain.usage.UsageFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.coupon.domain.CouponType;

// 한 항목에 쿠폰을 적용한 금액(d, f, s, D, 결제액, 지급액)을 본다. 검사 규칙은 CouponStackingRuleTest가 본다
class CouponAmountRuleTest {

    private static final long LECTURE = 7L;

    private final CouponCalculator calculator = new CouponCalculator();

    private ItemResult apply(long price, UsableCoupon... coupons) {
        return calculator.apply(item(LECTURE, price), List.of(coupons), MEMBER_ID, NOW);
    }

    @Test
    void 정률은_남은_금액에_할인율을_곱해_1HP_미만을_버린다() {
        ItemResult result = apply(9_999, seller(1, LECTURE).rate(15).build());

        // 9,999 × 15 / 100 = 1,499.85
        assertThat(result.discount()).isEqualTo(1_499);
        assertThat(result.buyerAmount()).isEqualTo(8_500);
    }

    @Test
    void 정액이_가격보다_크면_남은_금액까지만_할인해_결제액이_0이다() {
        ItemResult sellerResult = apply(3_000, seller(1, LECTURE).amount(5_000).build());
        ItemResult platformResult = apply(3_000, platformAny(2).amount(5_000).build());

        assertThat(sellerResult.discount()).isEqualTo(3_000);
        assertThat(sellerResult.buyerAmount()).isZero();
        assertThat(sellerResult.sellerPayout()).isZero();
        assertThat(platformResult.discount()).isEqualTo(3_000);
        assertThat(platformResult.buyerAmount()).isZero();
        assertThat(platformResult.sellerPayout()).isEqualTo(3_000);
    }

    @Test
    void 모든_강의_대상_정률은_장당_최대_할인액을_넘지_않는다() {
        UsableCoupon coupon = platformAny(1).rate(50).maxDiscount(3_000L).build();

        assertThat(apply(10_000, coupon).discount()).isEqualTo(3_000);
        assertThat(apply(4_000, coupon).discount()).isEqualTo(2_000);
    }

    @Test
    void 특정_강의_대상_플랫폼_쿠폰은_발행_때_강의_가격_기준_장당_예산을_넘지_않는다() {
        // 정률 M = floor(10,000 × 10 / 100) = 1,000. 강의 가격이 20,000으로 올라도 1,000까지다
        UsableCoupon rate = platformLecture(1, LECTURE).rate(10).build();
        // 정액 M = min(2,000, 1,500) = 1,500
        UsableCoupon amount = platformLecture(2, LECTURE).basePrice(1_500L).amount(2_000).build();

        assertThat(apply(20_000, rate).discount()).isEqualTo(1_000);
        assertThat(apply(3_000, amount).discount()).isEqualTo(1_500);
    }

    @Test
    void 판매자_쿠폰에는_장당_예산_상한이_없다() {
        ItemResult result = apply(20_000, seller(1, LECTURE).rate(10).build());

        assertThat(result.discount()).isEqualTo(2_000);
    }

    @Test
    void 판매자_쿠폰은_판매자가_플랫폼_쿠폰은_플랫폼이_부담한다() {
        ItemResult sellerResult = apply(10_000, seller(1, LECTURE).build());
        ItemResult platformResult = apply(10_000, platformAny(2).build());

        AppliedCoupon sellerCoupon = sellerResult.coupons().getFirst();
        assertThat(sellerCoupon.platformAmount()).isZero();
        assertThat(sellerCoupon.sellerAmount()).isEqualTo(1_000);
        assertThat(sellerResult.sellerPayout()).isEqualTo(9_000);

        AppliedCoupon platformCoupon = platformResult.coupons().getFirst();
        assertThat(platformCoupon.platformAmount()).isEqualTo(1_000);
        assertThat(platformCoupon.sellerAmount()).isZero();
        assertThat(platformResult.sellerPayout()).isEqualTo(10_000);
    }

    @Test
    void 판매자_쿠폰을_먼저_적용하고_플랫폼_정률은_남은_금액에_건다() {
        UsableCoupon seller = seller(1, LECTURE).rate(10).build();
        UsableCoupon platform = platformAny(2).rate(10).build();

        // 넘긴 순서와 관계없이 판매자 → 플랫폼이다
        ItemResult result = apply(10_000, platform, seller);

        assertThat(result.coupons()).extracting(AppliedCoupon::type, AppliedCoupon::applyOrder,
                        AppliedCoupon::baseAmount, AppliedCoupon::discount)
                .containsExactly(
                        tuple(CouponType.SELLER, 1, 10_000L, 1_000L),
                        tuple(CouponType.PLATFORM, 2, 9_000L, 900L));
        assertThat(result.discount()).isEqualTo(1_900);
        assertThat(result.platformDiscount()).isEqualTo(900);
        assertThat(result.sellerDiscount()).isEqualTo(1_000);
        assertThat(result.buyerAmount()).isEqualTo(8_100);
        assertThat(result.sellerPayout()).isEqualTo(9_000);
    }

    @Test
    void 쿠폰마다_d는_f_더하기_s이고_항목_D는_P를_넘지_않으며_지급액은_P_빼기_S다() {
        ItemResult result = apply(1_500, seller(1, LECTURE).amount(1_000).build(), platformAny(2).amount(1_000).build());

        result.coupons().forEach(coupon ->
                assertThat(coupon.discount()).isEqualTo(coupon.platformAmount() + coupon.sellerAmount()));
        assertThat(result.discount()).isEqualTo(result.platformDiscount() + result.sellerDiscount());
        assertThat(result.discount()).isEqualTo(1_500);
        assertThat(result.buyerAmount()).isZero();
        assertThat(result.sellerPayout()).isEqualTo(1_500 - result.sellerDiscount());
        assertThat(result.sellerPayout()).isEqualTo(result.buyerAmount() + result.platformDiscount());
    }

    @Test
    void 계산_결과는_지금_세대와_정책_버전을_예약_기대값으로_담는다() {
        ItemResult result = apply(10_000, seller(1, LECTURE).generation(3).policyVersion(2).build());

        assertThat(result.coupons().getFirst().expectedGeneration()).isEqualTo(3);
        assertThat(result.coupons().getFirst().policyVersion()).isEqualTo(2);
    }

    @Test
    void 정률_계산은_큰_금액에서도_넘치지_않는다() {
        assertThat(CouponCalculator.rateOf(Long.MAX_VALUE, 100)).isEqualTo(Long.MAX_VALUE);
        assertThat(CouponCalculator.rateOf(199, 50)).isEqualTo(99);
    }
}
