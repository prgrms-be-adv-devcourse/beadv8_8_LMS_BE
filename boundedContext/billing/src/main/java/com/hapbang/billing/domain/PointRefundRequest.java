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

// 여러 충전 건에 걸친 충전 HP 환불을 하나의 요청으로 묶고 요청액·완료액·상태를 보관한다.
@Entity
@Table(name = "point_refund_request", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_point_refund_request_idempotency_key", columnNames = {"idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_point_refund_request_1", columnList = "wallet_id, requested_at"),
                @Index(name = "idx_point_refund_request_2", columnList = "point_hold_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(completed_amount_hp >= 0 AND completed_amount_hp <= requested_amount_hp)")
public class PointRefundRequest extends BillingUpdatedEntity {

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "requested_amount_hp", nullable = false)
    private Long requestedAmountHp;

    @Column(name = "completed_amount_hp", nullable = false)
    private Long completedAmountHp;

    @Column(name = "point_hold_id", nullable = true)
    private Long pointHoldId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PointRefundRequestStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "requested_at", nullable = false, columnDefinition = "timestamptz")
    private Instant requestedAt;

    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant completedAt;

    // 빌더로 전달받은 지갑·요청액·완료액·선택적 보류·처리 상태와 시각으로 환불 요청을 초기화한다.
    @Builder
    private PointRefundRequest(
            Long walletId,
            Long requestedAmountHp,
            Long completedAmountHp,
            Long pointHoldId,
            PointRefundRequestStatus status,
            String idempotencyKey,
            Instant requestedAt,
            Instant completedAt
    ) {
        this.walletId = walletId;
        this.requestedAmountHp = requestedAmountHp;
        this.completedAmountHp = completedAmountHp;
        this.pointHoldId = pointHoldId;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
    }
}
