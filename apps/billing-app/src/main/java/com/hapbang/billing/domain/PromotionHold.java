package com.hapbang.billing.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 프로모션 보류. 주문 항목 보류(구매자 보류, PointHold) 하나에 붙은 쿠폰 하나의 플랫폼 몫이다. 한 보류에 쿠폰마다 한 행이다.
// 플랫폼 몫은 그 쿠폰의 예산 예약(BudgetReservation) 남은 예산에서 프로모션 지갑 보류 칸으로 옮긴 HP이고, 원장(PROMOTION_HOLD)은
// 예약(COUPON_BUDGET + 예약 ID)을 참조하며 작업 키로 이 행의 ID를 쓴다. 구매자 보류·예약은 ID로 참조한다(다른 Aggregate).
@Entity
@Table(name = "promotion_hold", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_promotion_hold_point_hold_id_coupon_id",
                        columnNames = {"point_hold_id", "coupon_id"})
        },
        indexes = {
                @Index(name = "idx_promotion_hold_1", columnList = "budget_reservation_id, status")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(amount_hp > 0) AND (generation >= 0)")
public class PromotionHold extends BillingUpdatedEntity {

    @Column(name = "point_hold_id", nullable = false, updatable = false)
    private Long pointHoldId;

    @Column(name = "budget_reservation_id", nullable = false, updatable = false)
    private Long budgetReservationId;

    @Column(name = "coupon_id", nullable = false, updatable = false)
    private Long couponId;

    // 쿠폰이 준 발급 쿠폰 ID와 그 사용 세대. 지갑은 기록·대조만 하고 해석하지 않는다
    @Column(name = "member_coupon_id", nullable = false, updatable = false)
    private Long memberCouponId;

    @Column(name = "generation", nullable = false, updatable = false)
    private Long generation;

    @Column(name = "amount_hp", nullable = false, updatable = false)
    private Long amountHp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PromotionHoldStatus status;

    @Column(name = "restoration_request_id", length = 200)
    private String restorationRequestId;

    public void pay() {
        requireHeld();
        status = PromotionHoldStatus.PAID;
    }

    public void restore(String releaseRequestId) {
        Objects.requireNonNull(releaseRequestId, "반환 요청 ID는 필수입니다.");
        requireHeld();
        restorationRequestId = releaseRequestId;
        status = PromotionHoldStatus.RESTORED;
    }

    private void requireHeld() {
        if (status != PromotionHoldStatus.HELD) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
    }

    private PromotionHold(Long pointHoldId, Long budgetReservationId, Long couponId, Long memberCouponId,
            long generation, long amountHp) {
        this.pointHoldId = pointHoldId;
        this.budgetReservationId = budgetReservationId;
        this.couponId = couponId;
        this.memberCouponId = memberCouponId;
        this.generation = generation;
        this.amountHp = amountHp;
        this.status = PromotionHoldStatus.HELD;
    }

    // 보류 중인 프로모션 보류를 만든다. 금액 0 이하는 INVALID_POINT_AMOUNT다(0 HP 원장을 만들지 않는다).
    public static PromotionHold held(Long pointHoldId, Long budgetReservationId, Long couponId, Long memberCouponId,
            long generation, long amountHp) {
        Objects.requireNonNull(pointHoldId, "보류 ID는 필수입니다.");
        Objects.requireNonNull(budgetReservationId, "예산 예약 ID는 필수입니다.");
        Objects.requireNonNull(couponId, "쿠폰 ID는 필수입니다.");
        Objects.requireNonNull(memberCouponId, "발급 쿠폰 ID는 필수입니다.");
        if (generation < 0) {
            throw new IllegalArgumentException("사용 세대는 0 이상입니다.");
        }
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        return new PromotionHold(pointHoldId, budgetReservationId, couponId, memberCouponId, generation, amountHp);
    }
}
