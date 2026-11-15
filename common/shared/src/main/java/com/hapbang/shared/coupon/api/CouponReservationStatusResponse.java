package com.hapbang.shared.coupon.api;

import java.util.List;

/**
 * 쿠폰 예약 상태 조회 내부 API({@code GET /api/v1/internal/coupons/reservations/{workId}})의 응답. 강의가 예약 결과를 모를 때
 * (시간 초과 등) 확인한다.
 *
 * @param workId     예약 작업 ID
 * @param status     작업 상태: {@code RESERVED}(예약됨), {@code RELEASED}(모두 해제됨), {@code REJECTED}(거절됨),
 *                   {@code NOT_FOUND}(기록 없음. 회원·주문이 다를 때도)
 * @param rejectCode 거절 코드(발급 쿠폰 ID 없이 코드만). {@code REJECTED}일 때만 값이 있다
 * @param items      주문 항목별 예약 결과. 예약 응답과 같은 모양(쿠폰이 없는 주문 항목은 빠짐)에 사용 시도별 상태를 더했다. {@code REJECTED}·{@code NOT_FOUND}면 빈 목록
 */
public record CouponReservationStatusResponse(
        String workId,
        String status,
        String rejectCode,
        List<Item> items
) {

    public CouponReservationStatusResponse {
        items = List.copyOf(items);
    }

    /**
     * 주문 항목 하나의 예약 결과.
     *
     * @param orderItemId      주문 항목 ID
     * @param discount         항목 할인 합(HP)
     * @param platformDiscount 플랫폼 부담 할인(HP)
     * @param sellerDiscount   판매자 부담 할인(HP)
     * @param coupons          적용 순서대로의 사용 시도
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
     * 사용 시도 하나.
     *
     * @param memberCouponId     발급 쿠폰 ID
     * @param couponId           쿠폰 정책 ID
     * @param reservedGeneration 예약으로 오른 generation
     * @param applyOrder         항목 안 적용 순서
     * @param baseAmount         적용 직전 남은 금액(HP)
     * @param discount           이 쿠폰의 할인(HP)
     * @param platformAmount     플랫폼 부담분(HP)
     * @param sellerAmount       판매자 부담분(HP)
     * @param policyVersion      예약 때 확인한 쿠폰 정책 버전
     * @param status             사용 시도 상태: {@code RESERVED}, {@code RELEASED}
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
            int policyVersion,
            String status
    ) {
    }
}
