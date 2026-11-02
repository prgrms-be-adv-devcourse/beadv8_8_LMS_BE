package com.hapbang.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;

// 지갑의 HP 종류별 가용·보류 증감을 기록하는 추가 전용 원장이며, 정정은 원본을 참조하는 역분개로 남긴다.
@Entity
@Table(name = "point_ledger", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_point_ledger_idempotency_key", columnNames = {"idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_point_ledger_1", columnList = "wallet_id, point_type, balance_bucket"),
                @Index(name = "idx_point_ledger_2", columnList = "operation_key"),
                @Index(name = "idx_point_ledger_3", columnList = "reference_type, reference_id"),
                @Index(name = "idx_point_ledger_4", columnList = "original_ledger_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Immutable
@Check(constraints = "(amount_hp <> 0)")
public class PointLedger extends BillingCreatedEntity {

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private Long walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "point_type", nullable = false, updatable = false)
    private PointType pointType;

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_bucket", nullable = false, updatable = false)
    private BalanceBucket balanceBucket;

    @Column(name = "amount_hp", nullable = false, updatable = false)
    private Long amountHp;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, updatable = false)
    private LedgerTransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, updatable = false)
    private BillingReferenceType referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private Long referenceId;

    @Column(name = "operation_key", nullable = false, updatable = false)
    private String operationKey;

    @Column(name = "original_ledger_id", nullable = true, updatable = false)
    private Long originalLedgerId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "description", nullable = true, columnDefinition = "text", updatable = false)
    private String description;

    @Column(name = "occurred_at", nullable = false, columnDefinition = "timestamptz", updatable = false)
    private Instant occurredAt;

    // 빌더로 새로 만든 원장 항목만 true이며, 지갑에 한 번 반영하면 false가 된다. DB에서 읽은 항목은 기본 생성자로 만들어져 false다.
    @Transient
    private boolean pendingApplication;

    // 빌더로 전달받은 지갑·HP 증감·작업키·참조 대상·발생 시각으로 원장 항목을 초기화한다.
    @Builder
    private PointLedger(
            Long walletId,
            PointType pointType,
            BalanceBucket balanceBucket,
            Long amountHp,
            LedgerTransactionType transactionType,
            BillingReferenceType referenceType,
            Long referenceId,
            String operationKey,
            Long originalLedgerId,
            String idempotencyKey,
            String description,
            Instant occurredAt
    ) {
        this.walletId = walletId;
        this.pointType = pointType;
        this.balanceBucket = balanceBucket;
        this.amountHp = amountHp;
        this.transactionType = transactionType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.operationKey = operationKey;
        this.originalLedgerId = originalLedgerId;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
        this.occurredAt = occurredAt;
        this.pendingApplication = true;
    }

    // 아직 지갑에 반영하지 않은, 빌더로 새로 만든 원장 항목인지 반환한다.
    boolean isPendingApplication() {
        return pendingApplication;
    }

    // 지갑에 반영했음을 표시해 같은 원장 항목이 다시 반영되지 않게 한다.
    void markApplied() {
        pendingApplication = false;
    }
}
