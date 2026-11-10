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
 * 상태는 RESERVED에서 시작해 해제(RELEASED), 결제 완료(PAID), 취소·반환 완료(CANCELED)로 바뀐다. 결제·취소 완료는 순서가 바뀌어
 * 와도 완료(취소·해제)가 우선이고, 상태를 바꾸지 않은 이벤트도 사실(이벤트 ID·시각)로 남긴다.
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

    /** 결제 완료 이벤트 ID. 상태를 바꾸지 않은 늦은 결제 완료도 사실로 남긴다 */
    @Column(length = 200)
    private String paidEventId;

    private Instant paidAt;

    /** 취소 완료 이벤트 ID. 상태를 바꾸지 않은 취소 완료도 사실로 남긴다 */
    @Column(length = 200)
    private String cancelEventId;

    private Instant cancelCompletedAt;

    /** 취소 완료로 되돌린 발급 쿠폰 상태(AVAILABLE·EXPIRED). 상태를 바꾼 취소 완료에만 있다 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MemberCouponStatus restoredTo;

    private Instant restoredAt;

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

    /**
     * 결제 완료 이벤트를 반영한다. 쿠폰 기한은 다시 보지 않는다(할인은 예약 성공 시점 기준으로 인정한다).
     * <ul>
     *     <li>RESERVED이고 발급 쿠폰이 이 시도의 generation으로 RESERVED면 PAID로 바꾼다({@link UsageEventOutcome#APPLIED}). 호출자는
     *     발급 쿠폰을 RESERVED → USED로 바꾼다({@link MemberCoupon#markUsed}).</li>
     *     <li>이미 PAID면 아무것도 바꾸지 않는다({@link UsageEventOutcome#ALREADY_APPLIED}).</li>
     *     <li>CANCELED·RELEASED(취소·해제가 먼저 끝남)이거나 발급 쿠폰이 다른 generation·다른 상태면 결제 사실만 남기고 상태는 그대로
     *     둔다({@link UsageEventOutcome#FACT_ONLY}). 늦은 결제 완료가 취소·해제 결과를 뒤집지 않는다.</li>
     * </ul>
     *
     * @param couponStatus     발급 쿠폰의 지금 상태
     * @param couponGeneration 발급 쿠폰의 지금 generation
     */
    public UsageEventOutcome applyPaid(String eventId, Instant paidAt, MemberCouponStatus couponStatus,
                                       long couponGeneration, Instant now) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(paidAt, "paidAt");
        if (status == CouponUsageStatus.PAID) {
            return UsageEventOutcome.ALREADY_APPLIED;
        }
        boolean applies = status == CouponUsageStatus.RESERVED && couponGeneration == generation
                && couponStatus == MemberCouponStatus.RESERVED;
        if (paidEventId == null) {
            this.paidEventId = eventId;
            this.paidAt = paidAt;
        }
        this.updatedAt = now;
        if (!applies) {
            return UsageEventOutcome.FACT_ONLY;
        }
        this.status = CouponUsageStatus.PAID;
        return UsageEventOutcome.APPLIED;
    }

    /**
     * 취소·반환 완료 이벤트를 반영한다. 돌아갈 상태는 반영하는 시점({@code now})의 기한으로 정한다.
     * <ul>
     *     <li>RESERVED·PAID이고 발급 쿠폰이 이 시도의 generation으로 RESERVED·USED면 CANCELED로 바꾸고, 발급 쿠폰이 돌아갈 상태
     *     ({@code expiresAt > now}면 AVAILABLE, 아니면 EXPIRED)를 남긴다({@link UsageEventOutcome#APPLIED}). 호출자는 발급 쿠폰을 그
     *     상태로 바꾼다({@link MemberCoupon#restore}). 결제 완료보다 먼저 온 취소 완료(RESERVED)도 여기다.</li>
     *     <li>이미 CANCELED면 아무것도 바꾸지 않는다({@link UsageEventOutcome#ALREADY_APPLIED}).</li>
     *     <li>RELEASED이거나 발급 쿠폰이 다른 generation·다른 상태면 취소 사실만 남긴다({@link UsageEventOutcome#FACT_ONLY}). 이전
     *     generation의 이벤트가 지금 사용 상태를 바꾸지 않는다.</li>
     * </ul>
     *
     * @param couponStatus     발급 쿠폰의 지금 상태
     * @param couponGeneration 발급 쿠폰의 지금 generation
     * @param couponExpiresAt  발급 쿠폰의 만료 시각
     */
    public UsageEventOutcome applyCancelCompleted(String eventId, Instant completedAt, MemberCouponStatus couponStatus,
                                                  long couponGeneration, Instant couponExpiresAt, Instant now) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(completedAt, "completedAt");
        if (status == CouponUsageStatus.CANCELED) {
            return UsageEventOutcome.ALREADY_APPLIED;
        }
        boolean applies = (status == CouponUsageStatus.RESERVED || status == CouponUsageStatus.PAID)
                && couponGeneration == generation
                && (couponStatus == MemberCouponStatus.RESERVED || couponStatus == MemberCouponStatus.USED);
        if (cancelEventId == null) {
            this.cancelEventId = eventId;
            this.cancelCompletedAt = completedAt;
        }
        this.updatedAt = now;
        if (!applies) {
            return UsageEventOutcome.FACT_ONLY;
        }
        this.status = CouponUsageStatus.CANCELED;
        this.restoredTo = MemberCoupon.returnStatusOf(couponExpiresAt, now);
        this.restoredAt = now;
        return UsageEventOutcome.APPLIED;
    }

    /** 이 사용 시도가 이 주문의 것인가 */
    public boolean belongsTo(long orderId) {
        return this.orderId == orderId;
    }
}
