package com.hapbang.billing.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 쿠폰 예산 예약. 쿠폰 하나의 플랫폼 부담 예산으로 프로모션 HP를 묶어 둔 행이다. 쿠폰당 한 건, 예약 작업 ID당 한 건이다.
// 예약 총액은 남은 예산 + 보류 중 + 지급됨 + 해제됨으로 나뉘고, 각 칸은 0 이상이다. 남은 예산 합은 프로모션 지갑의 예약 칸과 같고,
// 보류 중 합은 프로모션 지갑의 보류 칸과 같다. 원장(BUDGET_RESERVE·BUDGET_RELEASE)은 COUPON_BUDGET + 이 예약의 ID를 참조한다.
@Entity
@Table(name = "budget_reservation", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_budget_reservation_coupon_id", columnNames = {"coupon_id"}),
                @UniqueConstraint(name = "uk_budget_reservation_work_id", columnNames = {"work_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(amount_hp > 0) AND (remaining_hp >= 0) AND (held_hp >= 0) AND (paid_hp >= 0) AND (released_hp >= 0)"
        + " AND (remaining_hp::numeric + held_hp + paid_hp + released_hp = amount_hp::numeric) AND (policy_version >= 1)")
public class BudgetReservation extends BillingUpdatedEntity {

    @Column(name = "work_id", nullable = false, updatable = false, length = 200)
    private String workId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private Long walletId;

    @Column(name = "coupon_id", nullable = false, updatable = false)
    private Long couponId;

    // 예약할 때 쿠폰이 보낸 정책 버전
    @Column(name = "policy_version", nullable = false, updatable = false)
    private Integer policyVersion;

    // 예약 총액. 처음 예약한 금액이며 바뀌지 않는다
    @Column(name = "amount_hp", nullable = false, updatable = false)
    private Long amountHp;

    // 아직 보류·해제하지 않은 남은 예산
    @Column(name = "remaining_hp", nullable = false)
    private Long remainingHp;

    // 주문 항목에 보류 중인 예산
    @Column(name = "held_hp", nullable = false)
    private Long heldHp;

    // 판매자에게 지급한 예산
    @Column(name = "paid_hp", nullable = false)
    private Long paidHp;

    // 해제해 프로모션 지갑 가용 칸으로 되돌린 예산
    @Column(name = "released_hp", nullable = false)
    private Long releasedHp;

    private BudgetReservation(String workId, Long walletId, Long couponId, int policyVersion, long amountHp) {
        this.workId = workId;
        this.walletId = walletId;
        this.couponId = couponId;
        this.policyVersion = policyVersion;
        this.amountHp = amountHp;
        this.remainingHp = amountHp;
        this.heldHp = 0L;
        this.paidHp = 0L;
        this.releasedHp = 0L;
    }

    // 쿠폰 하나의 예산을 예약한다. 처음에는 총액 전부가 남은 예산이다. 금액 0 이하는 INVALID_POINT_AMOUNT로 거절한다.
    public static BudgetReservation reserve(String workId, Long walletId, Long couponId, int policyVersion,
            long amountHp) {
        Objects.requireNonNull(workId, "예약 작업 ID는 필수입니다.");
        Objects.requireNonNull(walletId, "지갑 ID는 필수입니다.");
        Objects.requireNonNull(couponId, "쿠폰 ID는 필수입니다.");
        if (policyVersion < 1) {
            throw new IllegalArgumentException("정책 버전은 1 이상입니다.");
        }
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        return new BudgetReservation(workId, walletId, couponId, policyVersion, amountHp);
    }

    // 남은 예산에서 해제한다(남은 예산 −, 해제됨 +). 보류 중인 예산은 해제하지 않는다. 금액 0 이하는 INVALID_POINT_AMOUNT다.
    // 남은 예산보다 많으면 불변식 위반이라 거절한다. 업무 거절(초과 해제)은 호출하는 쪽이 예약을 잠근 뒤 먼저 확인한다.
    public void release(long amountHp) {
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        if (amountHp > remainingHp) {
            throw new IllegalStateException("남은 예산보다 많이 해제할 수 없습니다.");
        }
        remainingHp -= amountHp;
        releasedHp += amountHp;
    }

    // 예약 총액이 남은 예산 + 보류 중 + 지급됨 + 해제됨과 같은지 확인한다(DB CHECK와 같은 규칙).
    public boolean isBalanced() {
        return amountHp == Math.addExact(Math.addExact(remainingHp, heldHp), Math.addExact(paidHp, releasedHp));
    }
}
