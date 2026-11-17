package com.hapbang.coupon.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발급 쿠폰(회원이 받은 한 장). 회원당 같은 쿠폰 정책에서 1장이고(UNIQUE(member_id, coupon_id)), 같은 발급 요청은 한 장이다
 * (UNIQUE(coupon_id, issue_request_id). 발급 요청 ID는 쿠폰 안에서만 유일하다). 행은 발급 확정 소비자가 만든다.
 * <p>
 * 쿠폰 정책은 ID로만 참조한다. 사용 정합성은 {@code @Version}이 아니라 generation 비교로 지킨다.
 */
@Entity
@Table(name = "member_coupon", schema = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long couponId;

    @Column(nullable = false, length = 100)
    private String issueRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberCouponStatus status;

    /** 사용 시도 세대. 새 사용 시도에서만 오른다 */
    @Column(nullable = false)
    private long generation;

    /** billing 해제로 영속 작업을 등록한 누적액. PENDING도 포함해 응답 유실 중 중복 등록을 막는다. */
    @Column(nullable = false, updatable = false)
    private long budgetReleasePlannedHp;

    /** 이 상태의 목표액까지 해제 작업 등록을 확인했다. HTTP 완료 여부와는 무관하다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, updatable = false)
    private MemberCouponStatus budgetReleaseCheckedStatus;

    private Long currentOrderItemId;

    /** 최초 접수 시각 */
    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant reservedAt;

    private Instant usedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private MemberCoupon(Long memberId, Long couponId, String issueRequestId, Instant issuedAt, Instant expiresAt,
                         Instant now) {
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.couponId = Objects.requireNonNull(couponId, "couponId");
        this.issueRequestId = Objects.requireNonNull(issueRequestId, "issueRequestId");
        this.status = MemberCouponStatus.AVAILABLE;
        this.generation = 0;
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 발급 확정으로 만든 한 장(AVAILABLE).
     *
     * @param issuedAt  최초 접수 시각(접수 기록의 값)
     * @param expiresAt 접수 때 정한 만료 시각({@link Coupon#expiresAtFor})
     */
    public static MemberCoupon issue(Long memberId, Long couponId, String issueRequestId, Instant issuedAt,
                                     Instant expiresAt, Instant now) {
        return new MemberCoupon(memberId, couponId, issueRequestId, issuedAt, expiresAt, now);
    }

    /** 만료 시각이 {@code expiresAt}인 발급 쿠폰이 {@code now}에 돌아갈 상태: {@code expiresAt > now}면 AVAILABLE, 아니면 EXPIRED */
    public static MemberCouponStatus returnStatusOf(Instant expiresAt, Instant now) {
        return isExpiredAt(expiresAt, now) ? MemberCouponStatus.EXPIRED : MemberCouponStatus.AVAILABLE;
    }

    /**
     * 만료 시각이 {@code expiresAt}인 발급 쿠폰이 {@code now}에 기한이 지났는가: {@code expiresAt <= now}. 사용 가능 판정
     * ({@code expiresAt > now})·해제·복원({@link #returnStatusOf})과 같은 경계다.
     */
    public static boolean isExpiredAt(Instant expiresAt, Instant now) {
        return !expiresAt.isAfter(now);
    }
}
