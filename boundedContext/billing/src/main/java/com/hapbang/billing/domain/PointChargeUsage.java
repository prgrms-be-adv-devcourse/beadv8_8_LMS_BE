package com.hapbang.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 충전 원금이 특정 보류에 배분된 금액과 그중 남은 보류·소진·반환 금액을 보관한다.
@Entity
@Table(name = "point_charge_usage", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_point_charge_usage_charge_id_point_hold_id", columnNames = {"charge_id", "point_hold_id"})
        },
        indexes = {
                @Index(name = "idx_point_charge_usage_1", columnList = "point_hold_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(amount_hp > 0) AND (remaining_held_hp >= 0) AND (consumed_hp >= 0) AND (released_hp >= 0) AND (remaining_held_hp::numeric + consumed_hp + released_hp = amount_hp::numeric)")
public class PointChargeUsage extends BillingUpdatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charge_id", nullable = false)
    private PointCharge charge;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "point_hold_id", nullable = false)
    private PointHold pointHold;

    @Column(name = "amount_hp", nullable = false)
    private Long amountHp;

    @Column(name = "remaining_held_hp", nullable = false)
    private Long remainingHeldHp;

    @Column(name = "consumed_hp", nullable = false)
    private Long consumedHp;

    @Column(name = "released_hp", nullable = false)
    private Long releasedHp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PointChargeUsageStatus status;

    // 빌더로 전달받은 충전 건·보류와 각 배분 금액·상태로 충전 원금 사용 내역을 초기화한다.
    @Builder
    private PointChargeUsage(
            PointCharge charge,
            PointHold pointHold,
            Long amountHp,
            Long remainingHeldHp,
            Long consumedHp,
            Long releasedHp,
            PointChargeUsageStatus status
    ) {
        this.charge = charge;
        this.pointHold = pointHold;
        this.amountHp = amountHp;
        this.remainingHeldHp = remainingHeldHp;
        this.consumedHp = consumedHp;
        this.releasedHp = releasedHp;
        this.status = status;
    }
}
