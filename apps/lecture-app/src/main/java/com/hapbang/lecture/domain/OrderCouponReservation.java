package com.hapbang.lecture.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hapbang.shared.coupon.api.CouponReserveResponse;

/**
 * 쿠폰 예약 응답을 주문에 남긴 할인 스냅샷과 대조한다. 쿠폰은 예약할 때 요청 근거와 다시 계산한 금액을 대조하므로 정상이면 늘 같다.
 * 다르면 구매자가 본 금액과 실제 할인이 어긋난 것이라 그 주문은 결제하지 않는다.
 * <ul>
 *     <li>쿠폰을 쓴 항목마다 응답 항목이 하나 있고, 쿠폰을 쓰지 않은 항목은 응답에 없다.</li>
 *     <li>항목의 할인 합·플랫폼 부담·판매자 부담이 스냅샷과 같다.</li>
 *     <li>쿠폰마다 발급 쿠폰·쿠폰 정책·적용 순서·적용 직전 금액·할인·부담 몫·정책 버전이 계산 근거와 같다.</li>
 * </ul>
 */
public final class OrderCouponReservation {

    private OrderCouponReservation() {
    }

    /**
     * @param items   주문 항목
     * @param coupons 주문 항목들에 쓴 쿠폰의 계산 근거
     * @return 어긋난 곳의 설명(로그용). 모두 같으면 빈 값
     */
    public static Optional<String> mismatch(Collection<OrderItem> items, Collection<OrderItemCoupon> coupons,
                                            CouponReserveResponse response) {
        Map<Long, List<OrderItemCoupon>> couponsByItem = coupons.stream()
                .sorted(Comparator.comparingInt(OrderItemCoupon::getApplyOrder))
                .collect(Collectors.groupingBy(OrderItemCoupon::getOrderItemId));
        Map<Long, CouponReserveResponse.Item> reserved = response.items().stream()
                .collect(Collectors.toMap(CouponReserveResponse.Item::orderItemId, Function.identity(),
                        (first, second) -> first));
        if (reserved.size() != response.items().size()) {
            return Optional.of("응답에 같은 주문 항목이 두 번 있다");
        }
        for (OrderItem item : items) {
            List<OrderItemCoupon> expected = couponsByItem.getOrDefault(item.getId(), List.of());
            CouponReserveResponse.Item actual = reserved.remove(item.getId());
            if (expected.isEmpty() && actual == null) {
                continue;
            }
            if (expected.isEmpty()) {
                return Optional.of("쿠폰을 쓰지 않은 항목이 응답에 있다 orderItemId=" + item.getId());
            }
            if (actual == null) {
                return Optional.of("쿠폰을 쓴 항목이 응답에 없다 orderItemId=" + item.getId());
            }
            if (actual.discount() != item.getDiscountHp() || actual.platformDiscount() != item.getPlatformDiscountHp()
                    || actual.sellerDiscount() != item.getSellerDiscountHp()) {
                return Optional.of("항목 할인이 다르다 orderItemId=" + item.getId());
            }
            if (actual.coupons().size() != expected.size()) {
                return Optional.of("항목의 쿠폰 수가 다르다 orderItemId=" + item.getId());
            }
            List<CouponReserveResponse.Coupon> actualCoupons = actual.coupons().stream()
                    .sorted(Comparator.comparingInt(CouponReserveResponse.Coupon::applyOrder))
                    .toList();
            for (int i = 0; i < expected.size(); i++) {
                if (!sameBasis(expected.get(i), actualCoupons.get(i))) {
                    return Optional.of("쿠폰 계산 근거가 다르다 orderItemId=" + item.getId() + ", memberCouponId="
                            + expected.get(i).getMemberCouponId());
                }
            }
        }
        if (!reserved.isEmpty()) {
            return Optional.of("주문에 없는 항목이 응답에 있다 orderItemIds=" + reserved.keySet());
        }
        return Optional.empty();
    }

    private static boolean sameBasis(OrderItemCoupon expected, CouponReserveResponse.Coupon actual) {
        return expected.getMemberCouponId() == actual.memberCouponId()
                && expected.getCouponId() == actual.couponId()
                && expected.getApplyOrder() == actual.applyOrder()
                && expected.getBaseAmountHp() == actual.baseAmount()
                && expected.getDiscountHp() == actual.discount()
                && expected.getPlatformAmountHp() == actual.platformAmount()
                && expected.getSellerAmountHp() == actual.sellerAmount()
                && expected.getPolicyVersion() == actual.policyVersion();
    }
}
