package com.hapbang.lecture.app.dto;

import java.util.List;

import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 쿠폰 작업 상세 (관리자): 작업, 주문 항목별 쿠폰 계산 근거, 쿠폰 쪽 예약 상태")
public record OrderCouponWorkDetailResponse(
        OrderCouponWorkResponse work,
        @Schema(description = "주문 항목별로 쓴 쿠폰. 기대 generation은 계산 때 읽은 값, 예약 generation은 예약 결과를 받은 뒤의 값")
        List<ItemCoupon> coupons,
        @Schema(description = "쿠폰 쪽 예약 상태(이 주문의 예약 작업 ID로 조회): RESERVED·RELEASED·REJECTED·NOT_FOUND. "
                + "쿠폰을 부르지 못했으면 UNAVAILABLE이고 상세 조회는 실패시키지 않는다")
        String couponStatus,
        @Schema(description = "쿠폰 예약 상태 응답 그대로. 판단은 items[].coupons[].status로 한다(최상위 status는 결제 완료·취소된 "
                + "사용 시도도 RESERVED로 준다). UNAVAILABLE이면 없다", nullable = true)
        CouponReservationStatusResponse couponReservation
) {

    /** 주문 항목에 쓴 쿠폰 한 장 */
    public record ItemCoupon(
            Long orderItemId,
            Long memberCouponId,
            Long couponId,
            String couponType,
            int applyOrder,
            long discountHp,
            long expectedGeneration,
            @Schema(nullable = true)
            Long reservedGeneration
    ) {

        public static ItemCoupon of(OrderItemCoupon coupon) {
            return new ItemCoupon(coupon.getOrderItemId(), coupon.getMemberCouponId(), coupon.getCouponId(),
                    coupon.getCouponType(), coupon.getApplyOrder(), coupon.getDiscountHp(),
                    coupon.getExpectedGeneration(), coupon.getReservedGeneration());
        }
    }
}
