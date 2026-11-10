package com.hapbang.billing.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;

// 쿠폰 예산 해제 기록. 해제 작업 ID(workId) 하나로 예약의 남은 예산 일부를 프로모션 지갑 가용 칸으로 되돌린 사실이다. 해제 작업 ID당 한 건이다.
// 해제 대상(target)은 같은 쿠폰의 서로 다른 해제를 사람이 구분하도록 기록만 한다. 원장(BUDGET_RELEASE)은 예약(COUPON_BUDGET)을 참조하고
// 작업 키로 이 기록의 ID를 쓴다.
@Entity
@Table(name = "budget_release", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_budget_release_work_id", columnNames = {"work_id"})
        },
        indexes = {
                @Index(name = "idx_budget_release_1", columnList = "budget_reservation_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Immutable
@Check(constraints = "(amount_hp > 0)")
public class BudgetRelease extends BillingCreatedEntity {

    @Column(name = "work_id", nullable = false, updatable = false, length = 200)
    private String workId;

    @Column(name = "budget_reservation_id", nullable = false, updatable = false)
    private Long budgetReservationId;

    @Column(name = "amount_hp", nullable = false, updatable = false)
    private Long amountHp;

    // 해제 대상(예: UNISSUED:{수량}, MEMBER_COUPON:{id}, ADMIN). 기록만 하고 해석하지 않는다
    @Column(name = "target", nullable = false, updatable = false, length = 200)
    private String target;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    private BudgetRelease(String workId, Long budgetReservationId, long amountHp, String target, String reason) {
        this.workId = workId;
        this.budgetReservationId = budgetReservationId;
        this.amountHp = amountHp;
        this.target = target;
        this.reason = reason;
    }

    // 해제 기록을 만든다. 금액은 1 HP 이상이며 0 이하는 INVALID_POINT_AMOUNT로 거절한다(0 HP 원장을 만들지 않는다).
    public static BudgetRelease of(String workId, Long budgetReservationId, long amountHp, String target,
            String reason) {
        Objects.requireNonNull(workId, "해제 작업 ID는 필수입니다.");
        Objects.requireNonNull(budgetReservationId, "예산 예약 ID는 필수입니다.");
        Objects.requireNonNull(target, "해제 대상은 필수입니다.");
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        return new BudgetRelease(workId, budgetReservationId, amountHp, target, reason);
    }
}
