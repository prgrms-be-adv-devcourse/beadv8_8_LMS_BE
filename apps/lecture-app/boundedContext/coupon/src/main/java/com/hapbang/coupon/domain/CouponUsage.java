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

import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.UsageItem;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 쿠폰 사용 시도: 발급 쿠폰 한 장을 주문 항목 하나에 예약한 기록. 발급 쿠폰의 한 세대(generation)에 한 행이고
 * (UNIQUE(member_coupon_id, generation)), 한 장은 같은 주문 항목에 동시에 한 번만 예약한다(RESERVED만 보는 부분 유일 인덱스).
 * 해제한 뒤 같은 항목에 다시 예약하면 새 generation의 새 행이다.
 * <p>
 * 발급 쿠폰·쿠폰 정책·주문은 ID로만 참조한다. 금액은 예약 때 다시 계산한 값(적용 직전 남은 금액, d = f + s)을 그대로 남긴다.
 * 상태 변경의 동시성은 이 행이 아니라 발급 쿠폰의 generation 조건부 갱신이 가린다.
 */
@Entity
@Table(name = "coupon_usage", schema = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberCouponId;

    /** 예약으로 오른 발급 쿠폰 generation */
    @Column(nullable = false)
    private long generation;

    @Column(nullable = false)
    private Long couponId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponType couponType;

    @Column(nullable = false)
    private int policyVersion;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Long lectureId;

    /** 항목 원가격 P */
    @Column(nullable = false)
    private long itemPrice;

    @Column(nullable = false)
    private int applyOrder;

    /** 적용 직전 남은 금액 */
    @Column(nullable = false)
    private long baseAmount;

    /** 이 쿠폰의 할인 d */
    @Column(nullable = false)
    private long discountAmount;

    /** 플랫폼 부담 f */
    @Column(nullable = false)
    private long platformAmount;

    /** 판매자 부담 s */
    @Column(nullable = false)
    private long sellerAmount;

    @Column(nullable = false, length = 100)
    private String reservationWorkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponUsageStatus status;

    @Column(nullable = false)
    private Instant reservedAt;

    @Column(length = 100)
    private String releaseWorkId;

    @Column(length = 500)
    private String releaseReason;

    /** 보상 완료 근거. 기록만 하고 검증하지 않는다 */
    @Column(name = "release_compensation_ref", length = 200)
    private String releaseCompensationRef;

    /** 해제 뒤 발급 쿠폰 상태(AVAILABLE·EXPIRED) */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MemberCouponStatus releasedTo;

    private Instant releasedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private CouponUsage(long memberId, long orderId, UsageItem item, AppliedCoupon applied, String reservationWorkId,
                        Instant now) {
        this.memberCouponId = applied.memberCouponId();
        this.generation = applied.expectedGeneration() + 1;
        this.couponId = applied.couponId();
        this.couponType = Objects.requireNonNull(applied.type(), "type");
        this.policyVersion = applied.policyVersion();
        this.memberId = memberId;
        this.orderId = orderId;
        this.orderItemId = Objects.requireNonNull(item.orderItemId(), "orderItemId");
        this.lectureId = item.lectureId();
        this.itemPrice = item.price();
        this.applyOrder = applied.applyOrder();
        this.baseAmount = applied.baseAmount();
        this.discountAmount = applied.discount();
        this.platformAmount = applied.platformAmount();
        this.sellerAmount = applied.sellerAmount();
        this.reservationWorkId = Objects.requireNonNull(reservationWorkId, "reservationWorkId");
        this.status = CouponUsageStatus.RESERVED;
        this.reservedAt = Objects.requireNonNull(now, "now");
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 예약한 사용 시도. generation은 계산 때 읽은 값(기대값) + 1, 즉 예약 조건부 갱신으로 오른 값이다.
     *
     * @param item    주문 항목(주문 항목 ID가 있어야 한다)
     * @param applied 그 항목에 다시 계산해 확인한 쿠폰 한 장
     */
    public static CouponUsage reserve(long memberId, long orderId, UsageItem item, AppliedCoupon applied,
                                      String reservationWorkId, Instant now) {
        return new CouponUsage(memberId, orderId, item, applied, reservationWorkId, now);
    }

    /**
     * 예약을 해제한다(RESERVED → RELEASED). 발급 쿠폰을 되돌린 결과(AVAILABLE·EXPIRED)를 남긴다.
     *
     * @param releasedTo 해제 뒤 발급 쿠폰 상태. AVAILABLE·EXPIRED만
     */
    public void release(String releaseWorkId, String reason, String compensationRef, MemberCouponStatus releasedTo,
                        Instant now) {
        if (status != CouponUsageStatus.RESERVED) {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION);
        }
        if (releasedTo != MemberCouponStatus.AVAILABLE && releasedTo != MemberCouponStatus.EXPIRED) {
            throw new IllegalArgumentException("해제 뒤 상태는 AVAILABLE·EXPIRED만 됩니다: " + releasedTo);
        }
        this.status = CouponUsageStatus.RELEASED;
        this.releaseWorkId = Objects.requireNonNull(releaseWorkId, "releaseWorkId");
        this.releaseReason = reason;
        this.releaseCompensationRef = compensationRef;
        this.releasedTo = releasedTo;
        this.releasedAt = now;
        this.updatedAt = now;
    }

    /** 이 사용 시도가 이 주문의 것인가 */
    public boolean belongsTo(long orderId) {
        return this.orderId == orderId;
    }
}
