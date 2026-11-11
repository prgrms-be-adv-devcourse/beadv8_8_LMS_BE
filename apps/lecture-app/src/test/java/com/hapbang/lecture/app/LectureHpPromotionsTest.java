package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.shared.billing.dto.HoldPromotionDto;
import com.hapbang.shared.billing.dto.PromotionRestorationDto;

class LectureHpPromotionsTest {
    @Test
    void 플랫폼_몫이_있는_쿠폰만_예약_generation과_쿠폰_ID순으로_보낸다() {
        var platform = coupon(42, 901, 1_000L);
        platform.recordReserved(3L);
        var seller = coupon(43, 902, 0L);
        assertThat(LectureHpPromotions.of(List.of(seller, platform)))
                .containsExactly(new HoldPromotionDto(42, 901, 3, 1_000));
    }

    @Test
    void 플랫폼_몫에_예약_generation이_없으면_지갑에_보낼_수_없다() {
        assertThatThrownBy(() -> LectureHpPromotions.of(List.of(coupon(42, 901, 1_000))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 복구_목록은_순서와_무관하게_각_쿠폰_발급쿠폰_세대_금액이_정확히_같아야_한다() {
        List<HoldPromotionDto> expected = List.of(new HoldPromotionDto(42, 901, 3, 1_000),
                new HoldPromotionDto(43, 902, 5, 500));
        assertThat(LectureHpPromotions.restored(expected, List.of(new PromotionRestorationDto(43L, 902L, 5L, 500),
                new PromotionRestorationDto(42L, 901L, 3L, 1_000)))).isTrue();
        assertThat(LectureHpPromotions.restored(expected, List.of(new PromotionRestorationDto(42L, 901L, 3L, 1_000),
                new PromotionRestorationDto(43L, 902L, 4L, 500)))).isFalse();
        assertThat(LectureHpPromotions.restored(expected, List.of(new PromotionRestorationDto(42L, 901L, 3L, 1_000),
                new PromotionRestorationDto(42L, 901L, 3L, 1_000)))).isFalse();
    }

    private static OrderItemCoupon coupon(long couponId, long memberCouponId, long platformHp) {
        return OrderItemCoupon.create(31L, memberCouponId, couponId, platformHp == 0 ? "SELLER" : "PLATFORM", 0,
                10_000, 1_000, platformHp, 1_000 - platformHp, 1, 1);
    }
}
