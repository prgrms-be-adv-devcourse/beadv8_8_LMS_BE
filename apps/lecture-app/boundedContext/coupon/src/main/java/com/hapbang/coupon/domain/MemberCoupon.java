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
     * 결제 완료로 사용 완료한다: RESERVED → USED. 기한은 다시 보지 않는다(할인은 예약 성공 시점 기준으로 인정하므로 결제 중 만료돼도
     * USED로 끝낸다).
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
     * 취소·반환 완료로 복원한다: RESERVED·USED → 만료 전이면 AVAILABLE, 만료 뒤면 EXPIRED. 결제 완료보다 먼저 온 취소 완료는 RESERVED에서
     * 복원한다. 세대는 그대로다.
     *
     * @return 복원 뒤 상태
     * @throws CouponDomainException RESERVED·USED가 아니다({@code INVALID_STATUS_TRANSITION})
     */
    public MemberCouponStatus restore(Instant now) {
        if (status != MemberCouponStatus.RESERVED && status != MemberCouponStatus.USED) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION);
        }
        this.usedAt = null;
        return returnToOwner(now);
    }

    /** 해제·복원 때 돌아갈 상태: 만료 전(expiresAt > now)이면 AVAILABLE, 아니면 EXPIRED */
    public MemberCouponStatus returnStatusAt(Instant now) {
        return returnStatusOf(expiresAt, now);
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

    /**
     * 기한이 지난 미사용 쿠폰을 만료한다: AVAILABLE이고 {@code expiresAt <= now}일 때 EXPIRED. 세대는 그대로다. 저장은 만료 배치가 같은
     * 조건의 조건부 갱신으로 하고, 여기 규칙은 그 조건의 기준이다. 예약·사용 중(RESERVED·USED)인 쿠폰은 만료하지 않는다(할인은 예약 성공
     * 시점 기준으로 인정하고, 돌려받을 때 기한을 본다).
     *
     * @throws CouponDomainException AVAILABLE이 아니거나 아직 기한 전이다({@code INVALID_STATUS_TRANSITION})
     */
    public void expire(Instant now) {
        if (status != MemberCouponStatus.AVAILABLE || !isExpiredAt(expiresAt, now)) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION);
        }
        this.status = MemberCouponStatus.EXPIRED;
        this.updatedAt = now;
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
