package com.hapbang.shared.coupon.api;

import java.util.List;
import java.util.Objects;

/**
 * 쿠폰 계산 내부 API({@code POST /api/v1/internal/coupons/calculate})의 요청. 강의가 주문 전에 할인 금액을 계산하거나 다시 계산할 때 보낸다.
 * 이 record는 필수 값·빈 목록·음수 가격 같은 요청 구조만 검사한다. 항목 수·강의 중복·쿠폰 규칙은 쿠폰 모듈이 검사한다.
 *
 * @param memberId   주문하는 회원 ID
 * @param items      주문할 강의 목록(강의마다 한 줄)
 * @param selections 회원이 고른 쿠폰 매핑. {@code null}이면 추천, 빈 목록이면 쿠폰을 쓰지 않음, 값이 있으면 그 매핑만 검증·계산한다
 */
public record CouponCalculateRequest(
        Long memberId,
        List<Item> items,
        List<Selection> selections
) {

    public CouponCalculateRequest {
        Objects.requireNonNull(memberId, "memberId는 필수입니다.");
        Objects.requireNonNull(items, "items는 필수입니다.");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items는 한 개 이상이어야 합니다.");
        }
        items = List.copyOf(items);
        selections = selections == null ? null : List.copyOf(selections);
    }

    /**
     * 주문할 강의 한 줄.
     *
     * @param lectureId 강의 ID
     * @param sellerId  판매자 회원 ID
     * @param price     강의 가격(HP)
     */
    public record Item(
            Long lectureId,
            Long sellerId,
            Long price
    ) {

        public Item {
            Objects.requireNonNull(lectureId, "lectureId는 필수입니다.");
            Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
            Objects.requireNonNull(price, "price는 필수입니다.");
            if (price < 0) {
                throw new IllegalArgumentException("price는 0 이상이어야 합니다.");
            }
        }
    }

    /**
     * 발급 쿠폰 하나를 어느 강의에 쓸지 고른 매핑.
     *
     * @param memberCouponId 발급 쿠폰 ID
     * @param lectureId      쿠폰을 쓸 강의 ID
     */
    public record Selection(
            Long memberCouponId,
            Long lectureId
    ) {

        public Selection {
            Objects.requireNonNull(memberCouponId, "memberCouponId는 필수입니다.");
            Objects.requireNonNull(lectureId, "lectureId는 필수입니다.");
        }
    }
}
