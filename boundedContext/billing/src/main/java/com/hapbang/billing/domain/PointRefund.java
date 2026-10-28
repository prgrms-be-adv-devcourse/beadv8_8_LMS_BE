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
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 환불 요청에 속한 개별 충전 건의 PG 취소 금액과 처리 결과를 보관한다.
// idempotency_key는 이 충전 건 취소의 TossPayments Idempotency-Key이자 취소 사유에 넣는 표식이다(재요청·결과 확인에 같은 값을 쓴다, #260).
// pg_refund_key는 PG가 취소 성공에 준 거래 키(transactionKey)다.
@Entity
@Table(name = "point_refund", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_point_refund_idempotency_key", columnNames = {"idempotency_key"}),
                @UniqueConstraint(name = "uk_point_refund_refund_request_id_charge_id",
                        columnNames = {"refund_request_id", "charge_id"})
        },
        indexes = {
                @Index(name = "idx_point_refund_1", columnList = "refund_request_id"),
                @Index(name = "idx_point_refund_2", columnList = "wallet_id"),
                @Index(name = "idx_point_refund_3", columnList = "charge_id"),
                @Index(name = "idx_point_refund_4", columnList = "status, updated_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(refund_amount_hp > 0) AND (refund_amount_krw > 0) AND (cancel_request_count >= 0 AND result_check_count >= 0)")
public class PointRefund extends BillingUpdatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refund_request_id", nullable = false)
    private PointRefundRequest refundRequest;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "charge_id", nullable = false)
    private Long chargeId;

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

    // 실패·결과 불명확·운영자 확인 사유(PG 오류 코드 등) (#260)
    @Column(name = "failure_reason", nullable = true, columnDefinition = "text")
    private String failureReason;

    // PG 취소 요청(POST) 횟수(같은 멱등키 재요청 포함)와 최초·마지막 요청 시각 (#260). 최초 요청 시각은 멱등키 유효기간 판단 기준이다
    @Column(name = "cancel_request_count", nullable = false)
    private int cancelRequestCount;

    @Column(name = "first_cancel_requested_at", nullable = true, columnDefinition = "timestamptz")
    private Instant firstCancelRequestedAt;

    @Column(name = "last_cancel_requested_at", nullable = true, columnDefinition = "timestamptz")
    private Instant lastCancelRequestedAt;

    // 결과 확인(배치)에서 취소 결과를 확인하려고 시도한 조회 수(조회 실패 포함)와 마지막 시도 시각 (#260 정책 ②)
    @Column(name = "result_check_count", nullable = false)
    private int resultCheckCount;

    @Column(name = "last_result_checked_at", nullable = true, columnDefinition = "timestamptz")
    private Instant lastResultCheckedAt;

    // 처음 UNKNOWN이 된 시각. 이후 바뀌지 않는다 (#260 정책 ②)
    @Column(name = "unknown_since", nullable = true, columnDefinition = "timestamptz")
    private Instant unknownSince;

    // 운영자 확인 원인과 전환 시각(관리자 확인 대상 조회용) (#260 정책 ①②)
    @Enumerated(EnumType.STRING)
    @Column(name = "manual_review_reason", nullable = true)
    private RefundManualReviewReason manualReviewReason;

    @Column(name = "manual_review_at", nullable = true, columnDefinition = "timestamptz")
    private Instant manualReviewAt;

    // 빌더로 전달받은 환불 요청·충전 건·환불 금액·PG 처리 결과로 개별 환불 객체를 초기화한다.
    @Builder
    private PointRefund(
            PointRefundRequest refundRequest,
            Long walletId,
            Long chargeId,
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
        this.walletId = walletId;
        this.chargeId = chargeId;
        this.refundAmountHp = refundAmountHp;
        this.refundAmountKrw = refundAmountKrw;
        this.pgRefundKey = pgRefundKey;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
    }

    // 보류에 배분된 충전 건 하나의 취소를 PG 요청 전 상태(PENDING)로 만든다. 1 HP = 1원이다.
    public static PointRefund pending(PointRefundRequest request, Long chargeId, long amountHp, String idempotencyKey,
            Instant requestedAt) {
        return PointRefund.builder()
                .refundRequest(request)
                .walletId(request.getWalletId())
                .chargeId(chargeId)
                .refundAmountHp(amountHp)
                .refundAmountKrw(amountHp)
                .status(PointRefundStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .requestedAt(requestedAt)
                .build();
    }

    // PG 취소를 처음 요청하기 전에 PROCESSING으로 바꾸고 최초 요청 시각을 남긴다(요청 전에 커밋한다).
    public void startAttempt(Instant requestedAt) {
        requireStatus(PointRefundStatus.PENDING);
        status = PointRefundStatus.PROCESSING;
        firstCancelRequestedAt = requestedAt;
        recordCancelRetry(requestedAt);
    }

    // 결과 확인 중 같은 멱등키로 PG 취소를 다시 요청하기 전에 기록한다(상태는 그대로).
    public void recordCancelRetry(Instant requestedAt) {
        requireInFlight();
        cancelRequestCount++;
        lastCancelRequestedAt = requestedAt;
    }

    // 결과 확인(배치)에서 취소 결과 조회를 시도하기 전에 기록한다(조회가 실패해도 센다).
    public void recordResultCheck(Instant checkedAt) {
        requireInFlight();
        resultCheckCount++;
        lastResultCheckedAt = checkedAt;
    }

    // 같은 멱등키로 다시 POST해도 되는 안전 기간이 끝났는지 확인한다(최초 요청 시각 + window 이상이면 끝남, 경계 포함).
    public boolean isIdempotencyWindowOver(Instant now, Duration window) {
        return firstCancelRequestedAt == null || !now.isBefore(firstCancelRequestedAt.plus(window));
    }

    // UNKNOWN 자동 복구 한도에 도달했으면 그 원인을 돌려준다(시간 한도 먼저). UNKNOWN이 아니면 한도를 적용하지 않는다.
    public RefundManualReviewReason unknownLimitReached(Instant now, Duration timeLimit, int checkLimit) {
        if (status != PointRefundStatus.UNKNOWN || unknownSince == null) {
            return null;
        }
        if (!now.isBefore(unknownSince.plus(timeLimit))) {
            return RefundManualReviewReason.UNKNOWN_TIME_LIMIT;
        }
        if (resultCheckCount >= checkLimit) {
            return RefundManualReviewReason.UNKNOWN_CHECK_LIMIT;
        }
        return null;
    }

    // PG 취소 성공(확정 차감과 같은 트랜잭션).
    public void succeed(String transactionKey, Instant completedAt) {
        requireInFlight();
        status = PointRefundStatus.SUCCEEDED;
        pgRefundKey = transactionKey;
        this.completedAt = completedAt;
        failureReason = null;
    }

    // PG 취소 실패 확정, 또는 결제키를 읽을 수 없어 PG에 요청하지 못한 PENDING 건의 실패(반환과 같은 트랜잭션).
    public void fail(String reason, Instant failedAt) {
        if (status != PointRefundStatus.PENDING) {
            requireInFlight();
        }
        status = PointRefundStatus.FAILED;
        failureReason = reason;
        this.failedAt = failedAt;
    }

    // 결과 불명확. 보류를 유지하고 결과 확인에서 다시 판단한다. 처음 UNKNOWN이 된 시각은 한 번만 남긴다.
    public void markUnknown(String reason, Instant now) {
        requireInFlight();
        status = PointRefundStatus.UNKNOWN;
        failureReason = reason;
        if (unknownSince == null) {
            unknownSince = now;
        }
    }

    // 운영자 확인이 필요하다(보류 유지, 자동 반환·확정 차감·PG 재요청 대상에서 빠진다). 원인과 사유를 남긴다.
    public void requireManualReview(RefundManualReviewReason reviewReason, String reason, Instant now) {
        requireInFlight();
        status = PointRefundStatus.MANUAL_REVIEW_REQUIRED;
        manualReviewReason = reviewReason;
        manualReviewAt = now;
        failureReason = reason;
    }

    // PG에 취소 요청을 보내지 않은 건을 처리 대상에서 뺀다(반환과 같은 트랜잭션). 요청을 시작한 건(PENDING이 아님)은 거부한다.
    public void cancel(String reason, Instant canceledAt) {
        requireStatus(PointRefundStatus.PENDING);
        if (cancelRequestCount != 0) {
            throw new IllegalStateException("PG 취소 요청을 보낸 환불은 CANCELED로 끝낼 수 없습니다. refundId=" + getId());
        }
        status = PointRefundStatus.CANCELED;
        failureReason = reason;
        failedAt = canceledAt;
    }

    // PG 취소를 요청했고 결과를 확정하지 못한 상태(PROCESSING·UNKNOWN)인지 확인한다.
    public boolean isInFlight() {
        return status == PointRefundStatus.PROCESSING || status == PointRefundStatus.UNKNOWN;
    }

    private void requireInFlight() {
        if (!isInFlight()) {
            throw new IllegalStateException("PG 취소 결과를 기다리는 환불이 아닙니다. refundId=" + getId()
                    + ", status=" + status);
        }
    }

    private void requireStatus(PointRefundStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("환불 상태가 " + expected + "가 아닙니다. refundId=" + getId()
                    + ", status=" + status);
        }
    }
}
