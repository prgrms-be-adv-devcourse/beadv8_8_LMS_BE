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

import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 최초 보류 이후 발생한 부분 반환·부분 지급의 HP 종류별 금액과 참조 대상을 기록한다.
@Entity
@Table(name = "point_hold_detail", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_point_hold_detail_idempotency_key", columnNames = {"idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_point_hold_detail_1", columnList = "point_hold_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(charge_amount_hp >= 0) AND (revenue_amount_hp >= 0) AND (charge_amount_hp > 0 OR revenue_amount_hp > 0)")
public class PointHoldDetail extends BillingCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "point_hold_id", nullable = false)
    private PointHold pointHold;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false)
    private BillingReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private HoldDetailActionType actionType;

    @Column(name = "charge_amount_hp", nullable = false)
    private Long chargeAmountHp;

    @Column(name = "revenue_amount_hp", nullable = false)
    private Long revenueAmountHp;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    // 빌더로 전달받은 보류·참조 대상·처리 유형·HP 금액·멱등키로 부분 처리 상세를 초기화한다.
    @Builder
    private PointHoldDetail(
            PointHold pointHold,
            BillingReferenceType referenceType,
            Long referenceId,
            HoldDetailActionType actionType,
            Long chargeAmountHp,
            Long revenueAmountHp,
            String idempotencyKey
    ) {
        this.pointHold = pointHold;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.actionType = actionType;
        this.chargeAmountHp = chargeAmountHp;
        this.revenueAmountHp = revenueAmountHp;
        this.idempotencyKey = idempotencyKey;
    }

    // 보류에서 결제자에게 되돌린 반환 한 건을 HP 종류별 금액으로 기록한다. 참조 대상은 보류의 업무 대상과 같다.
    public static PointHoldDetail released(PointHold pointHold, long chargeAmountHp, long revenueAmountHp,
            String idempotencyKey) {
        return PointHoldDetail.builder()
                .pointHold(pointHold)
                .referenceType(pointHold.getReferenceType())
                .referenceId(pointHold.getReferenceId())
                .actionType(HoldDetailActionType.RELEASE)
                .chargeAmountHp(chargeAmountHp)
                .revenueAmountHp(revenueAmountHp)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    // 보류에서 받는 쪽(판매자·게스트)에게 옮긴 지급 한 건을 출처 HP 종류별 금액으로 기록한다. 참조 대상은 보류의 업무 대상과 같다.
    public static PointHoldDetail paidOut(PointHold pointHold, long chargeAmountHp, long revenueAmountHp,
            String idempotencyKey) {
        return PointHoldDetail.builder()
                .pointHold(pointHold)
                .referenceType(pointHold.getReferenceType())
                .referenceId(pointHold.getReferenceId())
                .actionType(HoldDetailActionType.PAYOUT)
                .chargeAmountHp(chargeAmountHp)
                .revenueAmountHp(revenueAmountHp)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    // 이 처리의 총액(충전 HP + 수익 HP)을 반환한다.
    public long totalAmountHp() {
        return Math.addExact(chargeAmountHp, revenueAmountHp);
    }
}
