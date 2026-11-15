package com.hapbang.shared.coupon.api;

import java.util.List;
import java.util.Objects;

/**
 * 쿠폰 예약 내부 API({@code POST /api/v1/internal/coupons/reservations})의 요청. 강의가 주문을 만들 때 계산 결과를 근거로 보낸다.
 * 쿠폰 모듈은 근거를 다시 검증·계산해 대조하고, 모든 쿠폰을 한 트랜잭션에서 예약하거나 하나도 예약하지 않는다.
 * 이 record는 필수 값·빈 목록·음수 가격 같은 요청 구조만 검사한다. workId 형식·항목 수·강의 중복·쿠폰 규칙은 쿠폰 모듈이 검사한다.
 *
 * @param memberId 주문하는 회원 ID
 * @param orderId  주문 ID
 * @param workId   예약 작업 ID(멱등 키). 1~100자 {@code [A-Za-z0-9:_-]}. 같은 workId·같은 내용이면 처음 결과를 다시 준다
 * @param items    주문 항목 목록
 */
public record CouponReserveRequest(
        Long memberId,
        Long orderId,
        String workId,
        List<Item> items
) {

    public CouponReserveRequest {
        Objects.requireNonNull(memberId, "memberId는 필수입니다.");
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(workId, "workId는 필수입니다.");
        Objects.requireNonNull(items, "items는 필수입니다.");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items는 한 개 이상이어야 합니다.");
        }
        items = List.copyOf(items);
    }

    /**
     * 주문 항목 하나와 거기에 쓸 쿠폰들. 쿠폰이 없는 항목은 {@code coupons}를 빈 목록으로 보낸다.
     *
     * @param orderItemId 주문 항목 ID
     * @param lectureId   강의 ID
     * @param sellerId    판매자 회원 ID
     * @param price       주문 스냅샷의 강의 가격(HP)
     * @param coupons     이 항목에 쓸 쿠폰의 계산 근거
     */
    public record Item(
            Long orderItemId,
            Long lectureId,
            Long sellerId,
            Long price,
            List<Coupon> coupons
    ) {

        public Item {
            Objects.requireNonNull(orderItemId, "orderItemId는 필수입니다.");
            Objects.requireNonNull(lectureId, "lectureId는 필수입니다.");
            Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
            Objects.requireNonNull(price, "price는 필수입니다.");
            if (price < 0) {
                throw new IllegalArgumentException("price는 0 이상이어야 합니다.");
            }
            Objects.requireNonNull(coupons, "coupons는 필수입니다.");
            coupons = List.copyOf(coupons);
        }
    }

    /**
     * 계산 응답에서 받은 쿠폰 한 장의 근거.
     *
     * @param memberCouponId     발급 쿠폰 ID
     * @param expectedGeneration 계산 때 읽은 generation
     * @param policyVersion      계산 때 읽은 쿠폰 정책 버전
     * @param applyOrder         항목 안 적용 순서
     * @param discount           이 쿠폰의 할인(HP)
     * @param platformAmount     플랫폼 부담분(HP)
     * @param sellerAmount       판매자 부담분(HP)
     */
    public record Coupon(
            Long memberCouponId,
            Long expectedGeneration,
            Integer policyVersion,
            Integer applyOrder,
            Long discount,
            Long platformAmount,
            Long sellerAmount
    ) {

        public Coupon {
            Objects.requireNonNull(memberCouponId, "memberCouponId는 필수입니다.");
            Objects.requireNonNull(expectedGeneration, "expectedGeneration은 필수입니다.");
            Objects.requireNonNull(policyVersion, "policyVersion은 필수입니다.");
            Objects.requireNonNull(applyOrder, "applyOrder는 필수입니다.");
            Objects.requireNonNull(discount, "discount는 필수입니다.");
            Objects.requireNonNull(platformAmount, "platformAmount는 필수입니다.");
            Objects.requireNonNull(sellerAmount, "sellerAmount는 필수입니다.");
        }
    }
}
