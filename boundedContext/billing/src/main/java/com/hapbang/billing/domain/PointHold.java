package com.hapbang.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 거래별 최초 보류액과 남은 보류액을 충전·수익 HP로 구분해 보관한다.
@Entity
@Table(name = "point_hold", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_point_hold_idempotency_key", columnNames = {"idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_point_hold_1", columnList = "wallet_id, reference_type, reference_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(original_charge_hp >= 0) AND (original_revenue_hp >= 0) AND (held_charge_hp >= 0 AND held_charge_hp <= original_charge_hp) AND (held_revenue_hp >= 0 AND held_revenue_hp <= original_revenue_hp)")
public class PointHold extends BillingUpdatedEntity {

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false)
    private BillingReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column(name = "original_charge_hp", nullable = false)
    private Long originalChargeHp;

    @Column(name = "original_revenue_hp", nullable = false)
    private Long originalRevenueHp;

    @Column(name = "held_charge_hp", nullable = false)
    private Long heldChargeHp;

    @Column(name = "held_revenue_hp", nullable = false)
    private Long heldRevenueHp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PointHoldStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "held_at", nullable = false, columnDefinition = "timestamptz")
    private Instant heldAt;

    @Column(name = "released_at", nullable = true, columnDefinition = "timestamptz")
    private Instant releasedAt;

    @Column(name = "canceled_at", nullable = true, columnDefinition = "timestamptz")
    private Instant canceledAt;

    // 빌더로 전달받은 거래 대상·최초 보류액·잔여 보류액·상태·시각으로 보류 객체를 초기화한다.
    @Builder
    private PointHold(
            Long walletId,
            BillingReferenceType referenceType,
            Long referenceId,
            Long originalChargeHp,
            Long originalRevenueHp,
            Long heldChargeHp,
            Long heldRevenueHp,
            PointHoldStatus status,
            String idempotencyKey,
            Instant heldAt,
            Instant releasedAt,
            Instant canceledAt
    ) {
        this.walletId = walletId;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.originalChargeHp = originalChargeHp;
        this.originalRevenueHp = originalRevenueHp;
        this.heldChargeHp = heldChargeHp;
        this.heldRevenueHp = heldRevenueHp;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.heldAt = heldAt;
        this.releasedAt = releasedAt;
        this.canceledAt = canceledAt;
    }

    // 참조 대상의 최초 보류를 만든다. 남은 보류액은 최초 보류액과 같고 상태는 HELD다.
    public static PointHold held(Long walletId, BillingReferenceType referenceType, Long referenceId, long chargeHp,
            long revenueHp, String idempotencyKey, Instant heldAt) {
        return PointHold.builder()
                .walletId(walletId)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .originalChargeHp(chargeHp)
                .originalRevenueHp(revenueHp)
                .heldChargeHp(chargeHp)
                .heldRevenueHp(revenueHp)
                .status(PointHoldStatus.HELD)
                .idempotencyKey(idempotencyKey)
                .heldAt(heldAt)
                .build();
    }

    // 최초 보류 총액(충전 HP + 수익 HP)을 반환한다.
    public long originalTotalHp() {
        return Math.addExact(originalChargeHp, originalRevenueHp);
    }

    // 남은 보류 총액(충전 HP + 수익 HP)을 반환한다.
    public long heldTotalHp() {
        return Math.addExact(heldChargeHp, heldRevenueHp);
    }

    // 아직 반환·지급할 보류 HP가 남은 상태(HELD, PARTIALLY_PROCESSED)인지 확인한다.
    public boolean isOpen() {
        return status == PointHoldStatus.HELD || status == PointHoldStatus.PARTIALLY_PROCESSED;
    }

    // 남은 보류액에서 HP 종류별 지급액을 뺀다. 남은 보류가 0이 되면 반환 이력이 있으면 MIXED, 없으면 PAID로 끝나며,
    // 남으면 PARTIALLY_PROCESSED가 된다. 이미 끝난 보류는 HOLD_ALREADY_CLOSED, 남은 보류액을 넘으면 PAYOUT_AMOUNT_EXCEEDS_HELD로 거절한다.
    // released_at은 반환으로 끝날 때만 남긴다(지급으로 끝나면 비워 둔다).
    public void payout(long chargeHp, long revenueHp, boolean hasRelease) {
        if (chargeHp < 0 || revenueHp < 0 || chargeHp + revenueHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        if (!isOpen() || heldTotalHp() == 0) {
            throw new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED);
        }
        if (chargeHp > heldChargeHp || revenueHp > heldRevenueHp) {
            throw new BillingException(BillingErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD);
        }
        heldChargeHp -= chargeHp;
        heldRevenueHp -= revenueHp;
        if (heldTotalHp() > 0) {
            status = PointHoldStatus.PARTIALLY_PROCESSED;
            return;
        }
        status = hasRelease ? PointHoldStatus.MIXED : PointHoldStatus.PAID;
    }

    // 남은 보류액에서 HP 종류별 반환액을 뺀다. 남은 보류가 0이 되면 지급 이력이 있으면 MIXED, 없으면 RELEASED로 끝내고 반환 시각을 남기며,
    // 남으면 PARTIALLY_PROCESSED가 된다. 이미 끝난 보류는 HOLD_ALREADY_CLOSED, 남은 보류액을 넘으면 RELEASE_AMOUNT_EXCEEDS_HELD로 거절한다.
    public void release(long chargeHp, long revenueHp, boolean hasPayout, Instant releasedAt) {
        if (chargeHp < 0 || revenueHp < 0 || chargeHp + revenueHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        if (!isOpen() || heldTotalHp() == 0) {
            throw new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED);
        }
        if (chargeHp > heldChargeHp || revenueHp > heldRevenueHp) {
            throw new BillingException(BillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD);
        }
        heldChargeHp -= chargeHp;
        heldRevenueHp -= revenueHp;
        if (heldTotalHp() > 0) {
            status = PointHoldStatus.PARTIALLY_PROCESSED;
            return;
        }
        status = hasPayout ? PointHoldStatus.MIXED : PointHoldStatus.RELEASED;
        this.releasedAt = releasedAt;
    }
}
