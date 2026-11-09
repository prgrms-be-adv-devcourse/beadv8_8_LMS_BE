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

    /** 지금 사용할 수 있는가: AVAILABLE이고 만료 전(expiresAt > now) */
    public boolean isUsableAt(Instant now) {
        return status == MemberCouponStatus.AVAILABLE && expiresAt.isAfter(now);
    }

    // 사용 상태 전이. 저장은 같은 조건의 conditional update(세대 비교) 한 문장으로 하고, 여기 규칙은 그 조건의 기준이다

    /**
     * 새 사용 시도로 예약한다: AVAILABLE → RESERVED, 세대 + 1.
     *
     * @throws CouponDomainException 본인 소유가 아니거나, AVAILABLE이 아니거나, 만료되었다({@code COUPON_NOT_USABLE})
     */
    public void reserve(long memberId, long orderItemId, Instant now) {
        if (this.memberId != memberId || !isUsableAt(now)) {
            throw new CouponDomainException(CouponDomainError.COUPON_NOT_USABLE);
        }
        this.status = MemberCouponStatus.RESERVED;
        this.generation++;
        this.currentOrderItemId = orderItemId;
        this.reservedAt = now;
        this.updatedAt = now;
    }

    /**
     * 예약을 해제한다: RESERVED → 만료 전이면 AVAILABLE, 만료 뒤면 EXPIRED. 세대는 그대로다.
     *
     * @return 해제 뒤 상태
     * @throws CouponDomainException RESERVED가 아니다({@code INVALID_STATUS_TRANSITION})
     */
    public MemberCouponStatus releaseReservation(Instant now) {
        requireStatus(MemberCouponStatus.RESERVED);
        return returnToOwner(now);
    }

    /**
     * 결제 완료로 사용 완료한다: RESERVED → USED.
     *
     * @throws CouponDomainException RESERVED가 아니다({@code INVALID_STATUS_TRANSITION})
     */
    public void markUsed(Instant now) {
        requireStatus(MemberCouponStatus.RESERVED);
        this.status = MemberCouponStatus.USED;
        this.usedAt = now;
        this.updatedAt = now;
    }

    /**
     * 취소·반환 완료로 복원한다: USED → 만료 전이면 AVAILABLE, 만료 뒤면 EXPIRED. 세대는 그대로다.
     *
     * @return 복원 뒤 상태
     * @throws CouponDomainException USED가 아니다({@code INVALID_STATUS_TRANSITION})
     */
    public MemberCouponStatus restore(Instant now) {
        requireStatus(MemberCouponStatus.USED);
        this.usedAt = null;
        return returnToOwner(now);
    }

    /** 해제·복원 때 돌아갈 상태: 만료 전(expiresAt > now)이면 AVAILABLE, 아니면 EXPIRED */
    public MemberCouponStatus returnStatusAt(Instant now) {
        return expiresAt.isAfter(now) ? MemberCouponStatus.AVAILABLE : MemberCouponStatus.EXPIRED;
    }

    private MemberCouponStatus returnToOwner(Instant now) {
        this.status = returnStatusAt(now);
        this.currentOrderItemId = null;
        this.reservedAt = null;
        this.updatedAt = now;
        return status;
    }

    private void requireStatus(MemberCouponStatus expected) {
        if (status != expected) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION);
        }
    }
}
