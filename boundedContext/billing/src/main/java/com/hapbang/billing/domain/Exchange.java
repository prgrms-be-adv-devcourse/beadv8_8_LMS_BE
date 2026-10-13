package com.hapbang.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 수익 HP 환전 요청의 보류 연결, 수수료, KRW 송금 예정액과 처리 상태를 보관한다.
@Entity
@Table(name = "exchange", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_exchange_idempotency_key", columnNames = {"idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_exchange_1", columnList = "wallet_id"),
                @Index(name = "idx_exchange_2", columnList = "point_hold_id"),
                @Index(name = "idx_exchange_3", columnList = "status, payout_scheduled_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(requested_amount_hp >= 1000 AND requested_amount_hp % 1000 = 0) AND (fee_amount_hp >= 0) AND (payout_amount_krw >= 0) AND (fee_amount_hp::numeric + payout_amount_krw = requested_amount_hp::numeric)")
public class Exchange extends BillingUpdatedEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "point_hold_id", nullable = true)
    private Long pointHoldId;

    @Column(name = "requested_amount_hp", nullable = false)
    private Long requestedAmountHp;

    @Column(name = "fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal feeRate;

    @Column(name = "fee_amount_hp", nullable = false)
    private Long feeAmountHp;

    @Column(name = "payout_amount_krw", nullable = false)
    private Long payoutAmountKrw;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExchangeStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "payout_scheduled_at", nullable = false, columnDefinition = "timestamptz")
    private Instant payoutScheduledAt;

    @Column(name = "requested_at", nullable = false, columnDefinition = "timestamptz")
    private Instant requestedAt;

    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant completedAt;

    @Column(name = "failed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant failedAt;

    @Column(name = "failure_reason", nullable = true, columnDefinition = "text")
    private String failureReason;

    // 빌더로 전달받은 회원·지갑·선택적 보류·환전 금액·수수료·처리 상태와 시각으로 환전 요청을 초기화한다.
    @Builder
    private Exchange(
            Long userId,
            Long walletId,
            Long pointHoldId,
            Long requestedAmountHp,
            BigDecimal feeRate,
            Long feeAmountHp,
            Long payoutAmountKrw,
            ExchangeStatus status,
            String idempotencyKey,
            Instant payoutScheduledAt,
            Instant requestedAt,
            Instant completedAt,
            Instant failedAt,
            String failureReason
    ) {
        this.userId = userId;
        this.walletId = walletId;
        this.pointHoldId = pointHoldId;
        this.requestedAmountHp = requestedAmountHp;
        this.feeRate = feeRate;
        this.feeAmountHp = feeAmountHp;
        this.payoutAmountKrw = payoutAmountKrw;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.payoutScheduledAt = payoutScheduledAt;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
        this.failureReason = failureReason;
    }
}
