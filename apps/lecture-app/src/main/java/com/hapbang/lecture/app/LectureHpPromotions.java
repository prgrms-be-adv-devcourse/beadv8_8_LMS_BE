package com.hapbang.lecture.app;

import java.util.Comparator;
import java.util.List;

import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.shared.billing.dto.HoldPromotionDto;
import com.hapbang.shared.billing.dto.PromotionRestorationDto;

/** 주문 항목에 영속화한 쿠폰 재원을 지갑 요청과 결과 대조에 쓰는 불변 값으로 만든다. */
final class LectureHpPromotions {
    private LectureHpPromotions() {
    }

    static List<HoldPromotionDto> of(List<OrderItemCoupon> coupons) {
        return coupons.stream().filter(c -> c.getPlatformAmountHp() > 0)
                .map(c -> {
                    if (c.getReservedGeneration() == null) {
                        throw new IllegalStateException("플랫폼 쿠폰의 예약 generation이 없습니다: " + c.getId());
                    }
                    return new HoldPromotionDto(c.getCouponId(), c.getMemberCouponId(), c.getReservedGeneration(),
                            c.getPlatformAmountHp());
                }).sorted(Comparator.comparingLong(HoldPromotionDto::couponId)).toList();
    }

    static long total(List<HoldPromotionDto> promotions) {
        return promotions.stream().map(HoldPromotionDto::amountHp).reduce(0L, Math::addExact);
    }

    static boolean restored(List<HoldPromotionDto> expected, List<PromotionRestorationDto> actual) {
        if (actual.size() != expected.size()) {
            return false;
        }
        List<PromotionRestorationDto> sorted = actual.stream()
                .sorted(Comparator.comparing(PromotionRestorationDto::couponId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        for (int i = 0; i < expected.size(); i++) {
            HoldPromotionDto e = expected.get(i);
            PromotionRestorationDto a = sorted.get(i);
            if (!Long.valueOf(e.couponId()).equals(a.couponId())
                    || !Long.valueOf(e.memberCouponId()).equals(a.memberCouponId())
                    || !Long.valueOf(e.generation()).equals(a.generation()) || e.amountHp() != a.amountHp()) {
                return false;
            }
        }
        return true;
    }
}
