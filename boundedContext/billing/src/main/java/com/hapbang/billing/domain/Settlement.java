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

// 강의·출연의 보류 HP를 수령자에게 지급하기 위한 대상·예정 시각·총액·지급 누계·상태를 보관한다.
@Entity
@Table(name = "settlement", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_settlement_idempotency_key", columnNames = {"idempotency_key"}),
                @UniqueConstraint(name = "uk_settlement_point_hold_id_source_type_source_id_payout_type", columnNames = {"point_hold_id", "source_type", "source_id", "payout_type"})
        },
        indexes = {
                @Index(name = "idx_settlement_1", columnList = "receiver_wallet_id"),
                @Index(name = "idx_settlement_2", columnList = "status, scheduled_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(paid_amount_hp >= 0 AND paid_amount_hp <= gross_amount_hp)")
public class Settlement extends BillingUpdatedEntity {

    @Column(name = "receiver_user_id", nullable = false)
    private Long receiverUserId;

    @Column(name = "receiver_wallet_id", nullable = false)
    private Long receiverWalletId;

    @Column(name = "point_hold_id", nullable = false)
    private Long pointHoldId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SettlementSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_type", nullable = false)
    private PayoutType payoutType;

    @Column(name = "gross_amount_hp", nullable = false)
    private Long grossAmountHp;

    @Column(name = "paid_amount_hp", nullable = false)
    private Long paidAmountHp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SettlementStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "scheduled_at", nullable = false, columnDefinition = "timestamptz")
    private Instant scheduledAt;

    @Column(name = "settled_at", nullable = true, columnDefinition = "timestamptz")
    private Instant settledAt;

    @Column(name = "failed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant failedAt;

    @Column(name = "failure_reason", nullable = true, columnDefinition = "text")
    private String failureReason;

    // 빌더로 전달받은 수령자·보류·지급 대상·금액·처리 상태와 시각으로 지급 객체를 초기화한다.
    @Builder
    private Settlement(
            Long receiverUserId,
            Long receiverWalletId,
            Long pointHoldId,
            SettlementSourceType sourceType,
            Long sourceId,
            PayoutType payoutType,
            Long grossAmountHp,
            Long paidAmountHp,
            SettlementStatus status,
            String idempotencyKey,
            Instant scheduledAt,
            Instant settledAt,
            Instant failedAt,
            String failureReason
    ) {
        this.receiverUserId = receiverUserId;
        this.receiverWalletId = receiverWalletId;
        this.pointHoldId = pointHoldId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.payoutType = payoutType;
        this.grossAmountHp = grossAmountHp;
        this.paidAmountHp = paidAmountHp;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.scheduledAt = scheduledAt;
        this.settledAt = settledAt;
        this.failedAt = failedAt;
        this.failureReason = failureReason;
    }
}
