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
// 상태 전이 (#260): REQUESTED → PROCESSING → COMPLETED | PARTIALLY_COMPLETED | FAILED | MANUAL_REVIEW_REQUIRED,
// REQUESTED → CANCELED(회원 취소, PG 취소 전에만). 완료액은 PG 취소가 성공해 확정 차감한 금액의 합이다.
@Entity
@Table(name = "point_refund_request", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_point_refund_request_idempotency_key", columnNames = {"idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_point_refund_request_1", columnList = "wallet_id, requested_at"),
                @Index(name = "idx_point_refund_request_2", columnList = "point_hold_id"),
                @Index(name = "idx_point_refund_request_3", columnList = "status, updated_at")
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

    // 자동 처리가 끝난 시각(COMPLETED·PARTIALLY_COMPLETED·FAILED) (#260)
    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant completedAt;

    // 회원이 PG 취소 전에 취소한 시각 (#260)
    @Column(name = "canceled_at", nullable = true, columnDefinition = "timestamptz")
    private Instant canceledAt;

    // 실패·운영자 확인 사유(첫 실패 충전 건의 PG 오류 코드 등, 운영자 확인용) (#260)
    @Column(name = "failure_reason", nullable = true, columnDefinition = "text")
    private String failureReason;

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

    // 환불 요청을 접수한다(REQUESTED). 보류는 저장 후 같은 트랜잭션에서 만들고 attachHold로 연결한다.
    public static PointRefundRequest requested(Long walletId, long amountHp, String idempotencyKey,
            Instant requestedAt) {
        return PointRefundRequest.builder()
                .walletId(walletId)
                .requestedAmountHp(amountHp)
                .completedAmountHp(0L)
                .status(PointRefundRequestStatus.REQUESTED)
                .idempotencyKey(idempotencyKey)
                .requestedAt(requestedAt)
                .build();
    }

    // 이 요청으로 만든 충전 HP 보류를 연결한다.
    public void attachHold(Long holdId) {
        requireStatus(PointRefundRequestStatus.REQUESTED);
        this.pointHoldId = holdId;
    }

    // 첫 PG 취소 요청 전에 PROCESSING으로 바꾼다. 이후 회원은 취소할 수 없다. 이미 PROCESSING이면 그대로 둔다.
    public void startProcessing() {
        if (status == PointRefundRequestStatus.REQUESTED) {
            status = PointRefundRequestStatus.PROCESSING;
            return;
        }
        requireStatus(PointRefundRequestStatus.PROCESSING);
    }

    // PG 취소가 성공해 확정 차감한 금액을 완료액에 더한다. 요청액을 넘으면 기록이 어긋난 것이다.
    public void addCompleted(long amountHp) {
        if (amountHp <= 0 || completedAmountHp + amountHp > requestedAmountHp) {
            throw new BillingException(BillingErrorCode.REFUND_STATE_INCONSISTENT);
        }
        completedAmountHp += amountHp;
    }

    // 자동 처리를 끝낸다: 전부 환불이면 COMPLETED, 일부면 PARTIALLY_COMPLETED, 없으면 FAILED.
    public void close(Instant closedAt, String reason) {
        requireOpen();
        if (completedAmountHp.equals(requestedAmountHp)) {
            status = PointRefundRequestStatus.COMPLETED;
        } else {
            status = completedAmountHp > 0 ? PointRefundRequestStatus.PARTIALLY_COMPLETED
                    : PointRefundRequestStatus.FAILED;
            failureReason = reason;
        }
        completedAt = closedAt;
    }

    // 운영자 확인이 필요한 상태로 둔다(자동 처리 중단, 해당 금액 보류 유지).
    public void requireManualReview(String reason) {
        requireOpen();
        status = PointRefundRequestStatus.MANUAL_REVIEW_REQUIRED;
        failureReason = reason;
    }

    // PG 취소 전(REQUESTED)인 요청을 회원이 취소한다.
    public void cancel(Instant canceledAt) {
        if (status != PointRefundRequestStatus.REQUESTED) {
            throw new BillingException(BillingErrorCode.REFUND_NOT_CANCELABLE);
        }
        status = PointRefundRequestStatus.CANCELED;
        this.canceledAt = canceledAt;
    }

    // 자동 처리 대상(REQUESTED·PROCESSING)인지 확인한다.
    public boolean isOpen() {
        return status == PointRefundRequestStatus.REQUESTED || status == PointRefundRequestStatus.PROCESSING;
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("환불 요청이 처리 중이 아닙니다. refundRequestId=" + getId()
                    + ", status=" + status);
        }
    }

    private void requireStatus(PointRefundRequestStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("환불 요청 상태가 " + expected + "가 아닙니다. refundRequestId=" + getId()
                    + ", status=" + status);
        }
    }
}
