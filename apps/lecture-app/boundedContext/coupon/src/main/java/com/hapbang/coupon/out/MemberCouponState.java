package com.hapbang.coupon.out;

import java.time.Instant;

import com.hapbang.coupon.domain.MemberCouponStatus;

/**
 * 조건부 갱신이 0행일 때 원인을 가르려고 DB에서 새로 읽은 발급 쿠폰 상태. 영속성 컨텍스트의 엔티티는 일괄 갱신 뒤 옛 값일 수 있어
 * 값 조회(projection)로 읽는다.
 */
public record MemberCouponState(long id, long memberId, MemberCouponStatus status, long generation, Instant expiresAt,
                                Long currentOrderItemId) {
}
