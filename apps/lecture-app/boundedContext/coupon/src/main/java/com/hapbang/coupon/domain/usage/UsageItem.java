package com.hapbang.coupon.domain.usage;

/**
 * 쿠폰을 적용할 주문 항목 하나(강의 하나).
 *
 * @param orderItemId 주문 항목 ID. 계산(calculate)에서는 주문 전이라 {@code null}이다
 * @param lectureId   강의 ID. 한 주문에서 같은 강의는 한 번만 나온다
 * @param sellerId    강의 판매자 ID
 * @param price       항목 원가격 P(HP, 0 이상)
 */
public record UsageItem(Long orderItemId, long lectureId, long sellerId, long price) {

    public UsageItem {
        if (price < 0) {
            throw new IllegalArgumentException("항목 가격은 0 이상이어야 합니다: " + price);
        }
    }
}
