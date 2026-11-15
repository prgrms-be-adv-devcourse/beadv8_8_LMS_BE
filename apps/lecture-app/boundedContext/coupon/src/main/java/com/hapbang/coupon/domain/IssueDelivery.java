package com.hapbang.coupon.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Kafka로 전달된 발급 요청 한 건(소비자가 DB에 확정할 내용). Lua 접수 때 정한 값을 그대로 싣는다: 만료 시각은 최초 접수 값이고
 * 소비자가 다시 계산하지 않는다.
 * <ul>
 *     <li>작업 ID({@link #operationId()}): 이벤트에 ID가 없으므로(팀 Kafka 규칙) 쿠폰 ID와 요청 ID로 만든다. 요청 ID는 쿠폰 안에서만
 *     유일하므로 쿠폰 ID를 함께 넣는다. 재발행·재전달된 같은 요청은 같은 작업 ID다.</li>
 *     <li>내용 식별값({@link #contentKey()}): 같은 작업 ID의 다른 내용(회원·epoch·접수 시각·만료 시각이 다름)을 충돌로 가른다.</li>
 *     <li>epoch 판정({@link #checkEpoch}, 규칙은 {@link IssueEpochRule}): 쿠폰의 현재 epoch와 같을 때만 확정한다. 다르면 이전
 *     (또는 아직 활성화되지 않은) 세대의 접수라 새 재고에 반영하지 않는다.</li>
 * </ul>
 */
public record IssueDelivery(String issueRequestId, long couponId, long memberId, long epoch, Instant issuedAt,
                            Instant expiresAt) {

    private static final String OPERATION_PREFIX = "coupon-issue:";

    /**
     * @throws IllegalArgumentException 값이 비었거나 형식이 틀렸다(재시도해도 같으므로 호출자가 격리한다)
     */
    public IssueDelivery {
        if (issueRequestId == null || issueRequestId.isBlank() || issueRequestId.length() > 100) {
            throw new IllegalArgumentException("발급 요청 ID가 비었거나 너무 길다");
        }
        if (couponId <= 0 || memberId <= 0 || epoch < Coupon.INITIAL_EPOCH) {
            throw new IllegalArgumentException("쿠폰 ID·회원 ID·epoch가 올바르지 않다 couponId=" + couponId
                    + ", memberId=" + memberId + ", epoch=" + epoch);
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("만료 시각이 접수 시각보다 뒤가 아니다");
        }
    }

    /** 소비 멱등 기록의 작업 ID: {@code coupon-issue:<쿠폰 ID>:<요청 ID>} */
    public String operationId() {
        return OPERATION_PREFIX + couponId + ":" + issueRequestId;
    }

    /** 요청 내용 식별값: {@code 회원 ID|epoch|접수 시각|만료 시각}(시각은 epoch 밀리초. Redis 접수 기록과 같은 정밀도) */
    public String contentKey() {
        return memberId + "|" + epoch + "|" + issuedAt.toEpochMilli() + "|" + expiresAt.toEpochMilli();
    }

    /** 쿠폰의 현재 epoch와 비교한다({@link IssueEpochRule#judge}) */
    public IssueEpochRule.Verdict checkEpoch(long couponEpoch) {
        return IssueEpochRule.judge(epoch, couponEpoch);
    }

    /** 이미 있는 DB 결과가 같은 요청(회원·epoch·최초 접수 시각)의 것인가. 다르면 같은 요청 ID의 다른 내용이다 */
    public boolean sameRequestAs(CouponIssueRequest existing) {
        return existing.getMemberId() == memberId
                && existing.getEpoch() == epoch
                && existing.getAcceptedAt().toEpochMilli() == issuedAt.toEpochMilli();
    }

    /** 발급 쿠폰 행(AVAILABLE). 발급 시각은 최초 접수 시각, 만료 시각은 이벤트 값 그대로다 */
    public MemberCoupon toMemberCoupon(Instant now) {
        return MemberCoupon.issue(memberId, couponId, issueRequestId, issuedAt, expiresAt, now);
    }

    /** 발급 확정 결과 */
    public CouponIssueRequest toIssued(Long memberCouponId, Instant now) {
        return CouponIssueRequest.issued(issueRequestId, memberId, couponId, epoch, issuedAt, memberCouponId, now);
    }

    /** 거절 확정 결과 */
    public CouponIssueRequest toRejected(String reason, Instant now) {
        return CouponIssueRequest.rejected(issueRequestId, memberId, couponId, epoch, issuedAt, reason, now);
    }
}
