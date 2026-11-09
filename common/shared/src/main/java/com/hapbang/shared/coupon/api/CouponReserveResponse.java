package com.hapbang.shared.coupon.api;

import java.util.List;

/**
 * 쿠폰 예약 내부 API({@code POST /api/v1/internal/coupons/reservations})의 응답. 요청의 모든 쿠폰이 예약되었을 때만 온다.
 *
 * @param workId 예약 작업 ID
 * @param status 작업 상태. 항상 {@code RESERVED}
 * @param items  주문 항목별 예약 결과. 저장한 사용 이력으로 만들어 쿠폰이 없는 주문 항목은 빠진다(처음 응답과 재응답이 같다)
 */
public record CouponReserveResponse(
        String workId,
        String status,
        List<Item> items
) {

    public CouponReserveResponse {
        items = List.copyOf(items);
    }

    /**
     * 주문 항목 하나의 예약 결과.
     *
     * @param orderItemId      주문 항목 ID
     * @param discount         항목 할인 합(HP)
     * @param platformDiscount 플랫폼 부담 할인(HP)
     * @param sellerDiscount   판매자 부담 할인(HP)
     * @param coupons          적용 순서대로의 예약한 쿠폰
     */
    public record Item(
            long orderItemId,
            long discount,
            long platformDiscount,
            long sellerDiscount,
            List<Coupon> coupons
    ) {

        public Item {
            coupons = List.copyOf(coupons);
        }
    }

    /**
     * 예약한 쿠폰 한 장(사용 시도 하나).
     *
     * @param memberCouponId     발급 쿠폰 ID
     * @param couponId           쿠폰 정책 ID
     * @param reservedGeneration 예약으로 오른 generation. 해제는 이 값으로 한다
     * @param applyOrder         항목 안 적용 순서
     * @param baseAmount         적용 직전 남은 금액(HP)
     * @param discount           이 쿠폰의 할인(HP)
     * @param platformAmount     플랫폼 부담분(HP)
     * @param sellerAmount       판매자 부담분(HP)
     * @param policyVersion      예약 때 확인한 쿠폰 정책 버전
     */
    public record Coupon(
            long memberCouponId,
            long couponId,
            long reservedGeneration,
            int applyOrder,
            long baseAmount,
            long discount,
            long platformAmount,
            long sellerAmount,
            int policyVersion
    ) {
    }
}
