package com.hapbang.coupon.domain.usage;

import java.util.List;

/**
 * 항목 하나의 계산 결과. D = Σd, F = Σf, S = Σs이고 구매자 결제액 = P − D, 판매자 지급액 = P − S다.
 *
 * @param discount         항목 할인 합 D
 * @param platformDiscount 플랫폼 부담 합 F
 * @param sellerDiscount   판매자 부담 합 S
 * @param buyerAmount      구매자 결제액 P − D(0 이상)
 * @param sellerPayout     판매자 지급액 P − S
 * @param coupons          적용 순서대로의 쿠폰. 쿠폰이 없으면 빈 목록
 */
public record ItemResult(
        UsageItem item,
        long discount,
        long platformDiscount,
        long sellerDiscount,
        long buyerAmount,
        long sellerPayout,
        List<AppliedCoupon> coupons
) {

    public ItemResult {
        coupons = List.copyOf(coupons);
    }

    /** 쿠폰 없이 원가격 그대로인 결과 */
    public static ItemResult noCoupon(UsageItem item) {
        return new ItemResult(item, 0, 0, 0, item.price(), item.price(), List.of());
    }
}
