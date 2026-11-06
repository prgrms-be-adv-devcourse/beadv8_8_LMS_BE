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
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 쿠폰 정책(발행 단위).
 * <ul>
 *     <li>판매자 쿠폰은 {@link #createSeller}, 플랫폼 쿠폰은 {@link #createPlatform}으로 만든다. 유형을 호출자가 값으로 고르지 않는다.
 *     플랫폼 쿠폰 발행자가 관리자인지는 app의 역할 검사가 본다.</li>
 *     <li>상태 전이: 판매자 DRAFT → REVIEW → OPEN/REJECTED, 플랫폼 DRAFT → OPEN, OPEN → SUSPENDED → OPEN, OPEN·SUSPENDED → CLOSED.
 *     그 밖은 {@link CouponDomainError#INVALID_STATUS_TRANSITION}이다.</li>
 *     <li>발급을 열기 전에 Redis 발급 저장소를 준비하고({@link #markRedisReady}), 플랫폼 쿠폰은 그보다 먼저 예산을 확보한다
 *     ({@link #recordBudgetReserved}). 준비 전에는 열지 않는다.</li>
 * </ul>
 */
@Entity
@Table(name = "coupon", schema = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    /** 이 쿠폰 정책의 첫 버전 */
    public static final int INITIAL_POLICY_VERSION = 1;

    /** 첫 발급 epoch. Redis 유실 복구 때 오른다 */
    public static final long INITIAL_EPOCH = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponType type;

    /** 신청자 회원 ID. 플랫폼 쿠폰은 발행한 관리자 */
    @Column(nullable = false)
    private Long applicantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponTargetType targetType;

    private Long lectureId;

    /** 발행 시 강의 가격 snapshot(HP). 특정 강의 대상만 */
    private Long basePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscountType discountType;

    @Column(nullable = false)
    private long discountValue;

    private Long maxDiscountAmount;

    @Column(nullable = false)
    private int quantity;

    /** DB에 확정된 발급 수 */
    @Column(nullable = false)
    private int issuedCount;

    @Column(nullable = false)
    private Instant issueStartAt;

    @Column(nullable = false)
    private Instant issueEndAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExpiryType expiryType;

    private Integer expiryDays;

    /** 고정 만료 시각(FIXED만). 발급 쿠폰의 만료 시각은 {@link #expiresAtFor}로 구한다 */
    @Column(name = "expires_at")
    private Instant fixedExpiresAt;

    @Column(nullable = false)
    private boolean stackable;

    @Column(nullable = false)
    private int policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponStatus status;

    @Column(length = 500)
    private String rejectReason;

    @Column(length = 100)
    private String budgetReservationId;

    @Column(nullable = false)
    private long epoch;

    @Column(nullable = false)
    private boolean redisReady;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    private Coupon(CouponType type, Long applicantId, CouponTerms terms, Instant now) {
        terms.validateFor(type);
        this.type = type;
        this.applicantId = Objects.requireNonNull(applicantId, "applicantId");
        this.targetType = terms.targetType();
        this.lectureId = terms.lectureId();
        this.basePrice = terms.basePrice();
        this.discountType = terms.discountType();
        this.discountValue = terms.discountValue();
        this.maxDiscountAmount = terms.maxDiscountAmount();
        this.quantity = terms.quantity();
        this.issuedCount = 0;
        this.issueStartAt = terms.issueStartAt();
        this.issueEndAt = terms.issueEndAt();
        this.expiryType = terms.expiryType();
        this.expiryDays = terms.expiryDays();
        this.fixedExpiresAt = terms.fixedExpiresAt();
        this.stackable = terms.stackable();
        this.policyVersion = INITIAL_POLICY_VERSION;
        this.status = CouponStatus.DRAFT;
        this.epoch = INITIAL_EPOCH;
        this.redisReady = false;
        this.createdBy = applicantId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 판매자 쿠폰 신청서(DRAFT). 특정 강의 대상만. 강의 소유자 확인은 호출자가 강의 조회로 한다 */
    public static Coupon createSeller(Long sellerId, CouponTerms terms, Instant now) {
        return new Coupon(CouponType.SELLER, sellerId, terms, now);
    }

    /**
     * 플랫폼 쿠폰(DRAFT). 관리자만 부른다(역할 검사는 app). 예산액이 long을 넘으면 발행 단계에서 거절한다.
     */
    public static Coupon createPlatform(Long adminId, CouponTerms terms, Instant now) {
        Coupon coupon = new Coupon(CouponType.PLATFORM, adminId, terms, now);
        coupon.terms().budgetAmount();
        return coupon;
    }

    /** 이 쿠폰의 발행 조건 */
    public CouponTerms terms() {
        return new CouponTerms(targetType, lectureId, basePrice, discountType, discountValue, maxDiscountAmount,
                quantity, issueStartAt, issueEndAt, expiryType, expiryDays, fixedExpiresAt, stackable);
    }

    /** 신청자(플랫폼 쿠폰은 발행한 관리자)인가 */
    public boolean isOwnedBy(long memberId) {
        return applicantId == memberId;
    }

    /** 판매자 쿠폰을 심사에 올린다: DRAFT → REVIEW */
    public void submitReview(Instant now) {
        requireType(CouponType.SELLER);
        requireStatus(CouponStatus.DRAFT);
        changeStatus(CouponStatus.REVIEW, now);
    }

    /** 판매자 쿠폰 심사 거절: REVIEW → REJECTED */
    public void reject(String reason, Instant now) {
        requireType(CouponType.SELLER);
        requireStatus(CouponStatus.REVIEW);
        if (reason == null || reason.isBlank()) {
            throw new CouponDomainException(CouponDomainError.REJECT_REASON_REQUIRED);
        }
        this.rejectReason = reason;
        changeStatus(CouponStatus.REJECTED, now);
    }

    /**
     * 플랫폼 쿠폰의 예산 예약 성공을 기록한다(첫 기록은 열기 전 DRAFT에서만). 같은 예약 ID로 다시 기록하면 상태와 관계없이 아무것도
     * 바꾸지 않는다(같은 예산 작업을 동시에 기다린 다른 열기 요청이 먼저 열었을 수 있다). 다른 예약 ID면
     * {@link CouponDomainError#BUDGET_RESERVATION_MISMATCH}다.
     */
    public void recordBudgetReserved(String reservationId, Instant now) {
        requireType(CouponType.PLATFORM);
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("예산 예약 ID가 비었습니다.");
        }
        if (reservationId.equals(budgetReservationId)) {
            return;
        }
        requireStatus(CouponStatus.DRAFT);
        if (budgetReservationId != null) {
            throw new CouponDomainException(CouponDomainError.BUDGET_RESERVATION_MISMATCH);
        }
        this.budgetReservationId = reservationId;
        this.updatedAt = now;
    }

    /**
     * Redis 발급 저장소(issue-control·stock·epoch) 준비 완료를 기록한다. 열기 직전 상태(판매자 REVIEW, 플랫폼 DRAFT)에서만 하고,
     * 플랫폼 쿠폰은 예산을 먼저 확보해야 한다. 이미 준비됐으면 아무것도 바꾸지 않는다.
     */
    public void markRedisReady(Instant now) {
        requireStatus(statusBeforeOpen());
        if (type == CouponType.PLATFORM && budgetReservationId == null) {
            throw new CouponDomainException(CouponDomainError.BUDGET_NOT_RESERVED);
        }
        if (redisReady) {
            return;
        }
        this.redisReady = true;
        this.updatedAt = now;
    }

    /**
     * 발급을 연다. 판매자 쿠폰은 승인(REVIEW → OPEN), 플랫폼 쿠폰은 발행(DRAFT → OPEN)이다.
     * Redis 준비가 끝나야 하고, 플랫폼 쿠폰은 예산 예약도 있어야 한다.
     */
    public void open(Instant now) {
        requireStatus(statusBeforeOpen());
        if (type == CouponType.PLATFORM && budgetReservationId == null) {
            throw new CouponDomainException(CouponDomainError.BUDGET_NOT_RESERVED);
        }
        if (!redisReady) {
            throw new CouponDomainException(CouponDomainError.REDIS_NOT_READY);
        }
        changeStatus(CouponStatus.OPEN, now);
    }

    /** 신규 발급 중단: OPEN → SUSPENDED. 이미 발급된 쿠폰은 건드리지 않는다 */
    public void suspend(Instant now) {
        requireStatus(CouponStatus.OPEN);
        changeStatus(CouponStatus.SUSPENDED, now);
    }

    /** 발급 재개: SUSPENDED → OPEN */
    public void resume(Instant now) {
        requireStatus(CouponStatus.SUSPENDED);
        changeStatus(CouponStatus.OPEN, now);
    }

    /** 발급 종료: OPEN·SUSPENDED → CLOSED */
    public void close(Instant now) {
        if (status != CouponStatus.OPEN && status != CouponStatus.SUSPENDED) {
            throw invalidTransition();
        }
        changeStatus(CouponStatus.CLOSED, now);
    }

    /** 플랫폼 쿠폰의 프로모션 예산액(HP). 판매자 쿠폰은 예산이 없어 {@link CouponDomainError#BUDGET_NOT_APPLICABLE}이다 */
    public long budgetAmount() {
        if (type != CouponType.PLATFORM) {
            throw new CouponDomainException(CouponDomainError.BUDGET_NOT_APPLICABLE);
        }
        return terms().budgetAmount();
    }

    /** 이 쿠폰 정책 버전의 예산 예약 작업 ID. 재시도 때 같은 값이다 */
    public String budgetReserveWorkId() {
        if (type != CouponType.PLATFORM) {
            throw new CouponDomainException(CouponDomainError.BUDGET_NOT_APPLICABLE);
        }
        Objects.requireNonNull(id, "저장하지 않은 쿠폰에는 예산 작업 ID가 없습니다.");
        return BudgetWork.reserveWorkIdOf(id, policyVersion);
    }

    /** 최초 접수 시각으로 정한 발급 쿠폰의 만료 시각 */
    public Instant expiresAtFor(Instant firstAcceptedAt) {
        return terms().expiresAtFor(firstAcceptedAt);
    }

    /** 아직 DB에 확정되지 않은 수량 */
    public int remainingQuantity() {
        return quantity - issuedCount;
    }

    private CouponStatus statusBeforeOpen() {
        return type == CouponType.SELLER ? CouponStatus.REVIEW : CouponStatus.DRAFT;
    }

    private void requireType(CouponType expected) {
        if (type != expected) {
            throw invalidTransition();
        }
    }

    private void requireStatus(CouponStatus expected) {
        if (status != expected) {
            throw invalidTransition();
        }
    }

    private CouponDomainException invalidTransition() {
        return new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION);
    }

    private void changeStatus(CouponStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }
}
