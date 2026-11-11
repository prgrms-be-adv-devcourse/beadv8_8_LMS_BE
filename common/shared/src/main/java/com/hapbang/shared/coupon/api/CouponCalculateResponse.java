package com.hapbang.shared.coupon.api;

import java.util.List;

/**
 * 쿠폰 계산 내부 API({@code POST /api/v1/internal/coupons/calculate})의 응답. 강의는 이 결과를 주문 스냅샷과 예약 요청의 근거로 쓴다.
 *
 * @param recommended      매핑을 생략해 쿠폰 모듈이 추천한 결과인지
 * @param optimal          추천 결과가 최대 할인으로 확인되었는지. 추천하고 탐색을 끝까지 마쳤을 때만 {@code true}다. 탐색 상한을 넘어
 *                         찾은 최선이거나 추천이 아니면(선택 매핑·미사용) {@code false}
 * @param items            요청한 강의 순서대로의 항목별 결과
 * @param totalDiscount    전체 할인 합(HP)
 * @param totalBuyerAmount 전체 구매자 결제액 합(HP)
 */
public record CouponCalculateResponse(
        boolean recommended,
        boolean optimal,
        List<Item> items,
        long totalDiscount,
        long totalBuyerAmount
) {

    public CouponCalculateResponse {
        items = List.copyOf(items);
    }

    /**
     * 강의 한 줄의 계산 결과. {@code discount = platformDiscount + sellerDiscount}, {@code buyerAmount = price - discount},
     * {@code sellerPayout = price - sellerDiscount}다.
     *
     * @param lectureId        강의 ID
     * @param price            강의 가격(HP)
     * @param discount         항목 할인 합(HP)
     * @param platformDiscount 플랫폼이 부담하는 할인(HP)
     * @param sellerDiscount   판매자가 부담하는 할인(HP)
     * @param buyerAmount      구매자 결제액(HP)
     * @param sellerPayout     판매자 지급액(HP)
     * @param coupons          적용 순서대로의 쿠폰별 결과
     */
    public record Item(
            long lectureId,
            long price,
            long discount,
            long platformDiscount,
            long sellerDiscount,
            long buyerAmount,
            long sellerPayout,
            List<Coupon> coupons
    ) {

        public Item {
            coupons = List.copyOf(coupons);
        }
    }

    /**
     * 항목에 적용한 쿠폰 한 장의 계산 근거. 예약 요청에 그대로 보낸다.
     *
     * @param memberCouponId     발급 쿠폰 ID
     * @param couponId           쿠폰 정책 ID
     * @param couponType         쿠폰 유형 이름: {@code SELLER}(판매자 쿠폰), {@code PLATFORM}(플랫폼 쿠폰)
     * @param applyOrder         항목 안 적용 순서(1부터. 판매자 쿠폰 → 플랫폼 쿠폰)
     * @param baseAmount         적용 직전 남은 금액(HP)
     * @param discount           이 쿠폰의 할인(HP). {@code discount = platformAmount + sellerAmount}
     * @param platformAmount     플랫폼 부담분(HP)
     * @param sellerAmount       판매자 부담분(HP)
     * @param expectedGeneration 계산 때 읽은 발급 쿠폰 generation. 예약은 이 값으로 CAS한다
     * @param policyVersion      계산 때 읽은 쿠폰 정책 버전
     */
    public record Coupon(
            long memberCouponId,
            long couponId,
            String couponType,
            int applyOrder,
            long baseAmount,
            long discount,
            long platformAmount,
            long sellerAmount,
            long expectedGeneration,
            int policyVersion
    ) {
    }
}
