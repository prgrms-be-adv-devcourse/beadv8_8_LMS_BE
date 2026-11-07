package com.hapbang.coupon.domain;

import java.time.Instant;
import java.util.Objects;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프로모션 예산 예약·해제 작업 한 건. 쿠폰 상태 변경과 같은 트랜잭션에 저장하고, billing 호출은 트랜잭션 밖에서 한다.
 * 작업 ID는 billing 멱등 키다. 결과를 모르면 PENDING으로 두고 같은 작업 ID로 다시 조회·호출한다.
 * <p>
 * 작업 ID를 쿠폰이 정하므로 저장은 항상 새 행 INSERT다({@link Persistable}). 같은 작업 ID를 다시 저장하면 기존 작업을 덮지 않고
 * PK 위반으로 실패한다. 재시도는 {@code findById}로 기존 작업을 찾아 이어 간다. 예약이 거절(FAILED)되면 그 행은 기록으로 남기고,
 * 다시 예약할 때 시도 번호를 올린 새 작업 ID로 새 행을 만든다({@link #reserveWorkIdOf}).
 */
@Entity
@Table(name = "budget_work", schema = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetWork implements Persistable<String> {

    /** 예산 예약의 첫 시도 번호 */
    public static final int FIRST_ATTEMPT = 1;

    @Id
    @Column(length = 100)
    private String workId;

    @Column(nullable = false)
    private Long couponId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BudgetWorkKind kind;

    @Column(nullable = false)
    private long amount;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BudgetWorkStatus status;

    /** DONE이면 예약 ID, FAILED면 실패 사유 */
    @Column(length = 500)
    private String result;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    private BudgetWork(String workId, Long couponId, BudgetWorkKind kind, long amount, String reason, Instant now) {
        if (amount < 0) {
            throw new IllegalArgumentException("예산 작업 금액은 0 이상이어야 합니다.");
        }
        this.workId = Objects.requireNonNull(workId, "workId");
        this.couponId = Objects.requireNonNull(couponId, "couponId");
        this.kind = kind;
        this.amount = amount;
        this.reason = reason;
        this.status = BudgetWorkStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 쿠폰 정책 버전의 예산 예약 작업 ID. 첫 시도는 {@code coupon-{couponId}-reserve-v{policyVersion}}, 거절(FAILED) 뒤 다시 예약하는
     * n번째 시도(n ≥ 2)는 {@code coupon-{couponId}-reserve-v{policyVersion}-a{n}}이다.
     * 쿠폰·정책 버전·시도 번호에서 결정적으로 만들어, 발급 열기 재시도가 같은 작업을 찾고 billing에 같은 멱등 키를 보낸다.
     * 시도 번호는 앞 시도가 거절됐을 때만 하나씩 올리므로 빈 번호가 없다.
     */
    public static String reserveWorkIdOf(long couponId, int policyVersion, int attempt) {
        if (attempt < FIRST_ATTEMPT) {
            throw new IllegalArgumentException("예산 예약 시도 번호는 1 이상이어야 합니다.");
        }
        String firstAttemptId = "coupon-" + couponId + "-reserve-v" + policyVersion;
        return attempt == FIRST_ATTEMPT ? firstAttemptId : firstAttemptId + "-a" + attempt;
    }

    /** 플랫폼 쿠폰의 예산 예약 작업(PENDING). 작업 ID는 {@link Coupon#budgetReserveWorkId(int)}다 */
    public static BudgetWork reserve(Coupon coupon, int attempt, Instant now) {
        return new BudgetWork(coupon.budgetReserveWorkId(attempt), coupon.getId(), BudgetWorkKind.RESERVE,
                coupon.budgetAmount(), null, now);
    }

    /** 예약 성공: PENDING → DONE. 같은 예약 ID로 다시 기록하면 아무것도 바꾸지 않는다 */
    public void markDone(String resultValue, Instant now) {
        if (status == BudgetWorkStatus.DONE && Objects.equals(result, resultValue)) {
            return;
        }
        requirePending();
        this.status = BudgetWorkStatus.DONE;
        this.result = resultValue;
        this.updatedAt = now;
    }

    /** 확정 거절: PENDING → FAILED. 결과를 모르면 이것을 부르지 않고 PENDING으로 둔다 */
    public void markFailed(String failureReason, Instant now) {
        if (status == BudgetWorkStatus.FAILED && Objects.equals(result, failureReason)) {
            return;
        }
        requirePending();
        this.status = BudgetWorkStatus.FAILED;
        this.result = failureReason;
        this.updatedAt = now;
    }

    private void requirePending() {
        if (status != BudgetWorkStatus.PENDING) {
            throw new CouponDomainException(CouponDomainError.INVALID_BUDGET_WORK_TRANSITION);
        }
    }

    @Override
    public String getId() {
        return workId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }
}
