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
@Check(constraints = "(charge_amount_hp >= 0) AND (revenue_amount_hp >= 0) AND (charge_amount_hp > 0 OR revenue_amount_hp > 0) AND (target_charge_id IS NULL OR (revenue_amount_hp = 0 AND charge_amount_hp > 0))")
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

    // 처리할 충전 건을 지정한 상세(CHARGE_ONLY 보류의 PG 결제 건별 처리, #241). 다른 Aggregate라 ID로 참조한다.
    // 비어 있으면 기존 규칙(반환 = 배분 역순, 지급·확정 차감 = 배분 순서)으로 충전 원금을 나눈다.
    @Column(name = "target_charge_id", nullable = true)
    private Long targetChargeId;

    // 빌더로 전달받은 보류·참조 대상·처리 유형·HP 금액·멱등키로 부분 처리 상세를 초기화한다.
    @Builder
    private PointHoldDetail(
            PointHold pointHold,
            BillingReferenceType referenceType,
            Long referenceId,
            HoldDetailActionType actionType,
            Long chargeAmountHp,
            Long revenueAmountHp,
            String idempotencyKey,
            Long targetChargeId
    ) {
        this.pointHold = pointHold;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.actionType = actionType;
        this.chargeAmountHp = chargeAmountHp;
        this.revenueAmountHp = revenueAmountHp;
        this.idempotencyKey = idempotencyKey;
        this.targetChargeId = targetChargeId;
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

    // 지정한 충전 건 하나에서만 반환한 충전 HP를 기록한다 (#241). targetChargeId가 null이면 기존 반환 상세와 같다.
    public static PointHoldDetail released(PointHold pointHold, long chargeAmountHp, long revenueAmountHp,
            String idempotencyKey, Long targetChargeId) {
        return PointHoldDetail.builder()
                .pointHold(pointHold)
                .referenceType(pointHold.getReferenceType())
                .referenceId(pointHold.getReferenceId())
                .actionType(HoldDetailActionType.RELEASE)
                .chargeAmountHp(chargeAmountHp)
                .revenueAmountHp(revenueAmountHp)
                .idempotencyKey(idempotencyKey)
                .targetChargeId(targetChargeId)
                .build();
    }

    // 보류에서 다른 회원에게 옮기지 않고 최종 차감한 확정 차감 한 건을 HP 종류별 금액으로 기록한다 (#241).
    // targetChargeId를 주면 그 충전 건 배분에서만 차감한 충전 HP다.
    public static PointHoldDetail captured(PointHold pointHold, long chargeAmountHp, long revenueAmountHp,
            String idempotencyKey, Long targetChargeId) {
        return PointHoldDetail.builder()
                .pointHold(pointHold)
                .referenceType(pointHold.getReferenceType())
                .referenceId(pointHold.getReferenceId())
                .actionType(HoldDetailActionType.CAPTURE)
                .chargeAmountHp(chargeAmountHp)
                .revenueAmountHp(revenueAmountHp)
                .idempotencyKey(idempotencyKey)
                .targetChargeId(targetChargeId)
                .build();
    }

    // 이 처리의 총액(충전 HP + 수익 HP)을 반환한다.
    public long totalAmountHp() {
        return Math.addExact(chargeAmountHp, revenueAmountHp);
    }
}
