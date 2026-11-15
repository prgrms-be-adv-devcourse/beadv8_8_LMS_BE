package com.hapbang.lecture.app.dto;

import java.util.Objects;

/**
 * 주문에서 구매자가 고른 쿠폰 한 장과 그 쿠폰을 쓸 강의.
 *
 * @param memberCouponId 발급 쿠폰 ID
 * @param lectureId      쿠폰을 쓸 강의 ID(주문할 강의 중 하나)
 */
public record OrderCouponSelection(Long memberCouponId, Long lectureId) {

    public OrderCouponSelection {
        Objects.requireNonNull(memberCouponId, "memberCouponId는 필수입니다.");
        Objects.requireNonNull(lectureId, "lectureId는 필수입니다.");
    }
}
