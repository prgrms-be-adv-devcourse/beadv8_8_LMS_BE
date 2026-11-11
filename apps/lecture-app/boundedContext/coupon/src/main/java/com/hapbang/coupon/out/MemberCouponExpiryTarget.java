package com.hapbang.coupon.out;

import java.time.Instant;

/** 만료 대상과 같은 회차의 다음 조회 위치. 실패한 행도 커서가 지나가 다음 대상의 만료를 막지 않는다. */
public record MemberCouponExpiryTarget(long id, Instant expiresAt) {
}
