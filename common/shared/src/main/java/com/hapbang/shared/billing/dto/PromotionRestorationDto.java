package com.hapbang.shared.billing.dto;

// 반환으로 원래 쿠폰 예산 예약에 복구한 플랫폼 몫이다. 발급 쿠폰과 사용 세대는 소비 모듈이 대조한다.
public record PromotionRestorationDto(Long couponId, Long memberCouponId, Long generation, long amountHp) {
}
