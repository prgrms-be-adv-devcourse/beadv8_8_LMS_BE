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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 환불 요청에 속한 개별 충전 건의 PG 취소 금액과 처리 결과를 보관한다.
@Entity
@Table(name = "point_refund", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_point_refund_idempotency_key", columnNames = {"idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_point_refund_1", columnList = "refund_request_id"),
                @Index(name = "idx_point_refund_2", columnList = "wallet_id"),
                @Index(name = "idx_point_refund_3", columnList = "charge_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(refund_amount_hp > 0) AND (refund_amount_krw > 0)")
public class PointRefund extends BillingUpdatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refund_request_id", nullable = false)
    private PointRefundRequest refundRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charge_id", nullable = false)
    private PointCharge charge;

    @Column(name = "refund_amount_hp", nullable = false)
    private Long refundAmountHp;

    @Column(name = "refund_amount_krw", nullable = false)
    private Long refundAmountKrw;

    @Column(name = "pg_refund_key", nullable = true)
    private String pgRefundKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PointRefundStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "requested_at", nullable = false, columnDefinition = "timestamptz")
    private Instant requestedAt;

    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant completedAt;

    @Column(name = "failed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant failedAt;

    // 빌더로 전달받은 환불 요청·충전 건·환불 금액·PG 처리 결과로 개별 환불 객체를 초기화한다.
    @Builder
    private PointRefund(
            PointRefundRequest refundRequest,
            Wallet wallet,
            PointCharge charge,
            Long refundAmountHp,
            Long refundAmountKrw,
            String pgRefundKey,
            PointRefundStatus status,
            String idempotencyKey,
            Instant requestedAt,
            Instant completedAt,
            Instant failedAt
    ) {
        this.refundRequest = refundRequest;
        this.wallet = wallet;
        this.charge = charge;
        this.refundAmountHp = refundAmountHp;
        this.refundAmountKrw = refundAmountKrw;
        this.pgRefundKey = pgRefundKey;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
    }
}
