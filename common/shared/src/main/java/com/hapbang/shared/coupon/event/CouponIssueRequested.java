package com.hapbang.shared.coupon.event;

import java.time.Instant;

/**
 * 선착순 발급 접수 한 건. 쿠폰 모듈이 Redis 접수 직후, 그리고 DB에 확정되지 않은 접수를 재발행할 때 Kafka 토픽
 * {@link CouponEventTopic#NAME}(key = couponId)으로 보낸다. 같은 요청은 같은 ID·같은 값으로 다시 보낸다. 접수는 완료가 아니며,
 * 소비자가 DB에 발급을 확정한다.
 *
 * @param issueRequestId 발급 요청 ID(클라이언트가 정한 멱등 키)
 * @param couponId       쿠폰 정책 ID
 * @param memberId       접수한 회원 ID
 * @param epoch          접수 당시 발급 epoch. 복구로 epoch가 바뀌면 옛 epoch의 기록을 구분한다
 * @param issuedAt       최초 접수 시각
 * @param expiresAt      발급 쿠폰의 만료 시각
 */
public record CouponIssueRequested(
        String issueRequestId,
        long couponId,
        long memberId,
        long epoch,
        Instant issuedAt,
        Instant expiresAt
) {
}
