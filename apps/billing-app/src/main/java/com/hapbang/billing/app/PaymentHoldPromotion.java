package com.hapbang.billing.app;

import java.util.Objects;

// 주문 항목 보류의 플랫폼 몫 하나(쿠폰 하나). 금액(0 이하 거절)은 BillingCommandValidator가 검사한다.
public record PaymentHoldPromotion(Long couponId, Long memberCouponId, long generation, long amountHp) {

    public PaymentHoldPromotion {
        Objects.requireNonNull(couponId, "쿠폰 ID는 필수입니다.");
        Objects.requireNonNull(memberCouponId, "발급 쿠폰 ID는 필수입니다.");
        if (couponId <= 0 || memberCouponId <= 0) {
            throw new IllegalArgumentException("쿠폰 ID와 발급 쿠폰 ID는 양수여야 합니다.");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("사용 세대는 0 이상이어야 합니다: " + generation);
        }
    }
}
