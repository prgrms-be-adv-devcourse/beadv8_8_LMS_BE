package com.hapbang.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 수익 HP 환전 요청의 보류 연결, 수수료, KRW 송금 예정액과 처리 상태를 보관한다.
// 신청(#285): 수수료·예상 수령액을 신청 시 확정해 저장하고, 신청과 같은 트랜잭션에서 수익 HP를 REVENUE_ONLY로 보류해 연결한다.
// 회원 취소는 관리자 승인 전(REQUESTED)에만 할 수 있다. 관리자 승인·거절은 #294(REQUESTED → APPROVED·REJECTED).
// 송금 상태 전이 (#304): APPROVED → PROCESSING(시도 저장) → COMPLETED(성공·확정 차감) | FAILED(시도 확정 실패, 보류 유지) |
// UNKNOWN(결과 불명·운영자 확인, 보류 유지), PROCESSING ↔ UNKNOWN(결과 확인). FAILED → PROCESSING(관리자 수동 재송금) |
// FINAL_FAILED(관리자 최종 실패 확정, 보류 전부 반환).
// payoutScheduledAt은 잠정 정산 예정 시각(매월 10일, 토·일이면 다음 월요일의 00:00 KST)이다. 공휴일을 반영하지 않아 실제 송금일로
// 단정하지 않는다. 공휴일 보정·지급 대상 마감은 정산 Scheduler Issue에서 최종 반영한다.
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

    /** 환전 수수료율 10% (루트 AGENTS.md 5장) */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.1000");
    /** 최소 환전 금액이자 환전 단위 */
    public static final long UNIT_HP = 1_000L;
    /** 거절 사유 최대 길이(앞뒤 공백 제외, #294) */
    public static final int REJECTION_REASON_MAX = 500;

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

    @Column(name = "canceled_at", nullable = true, columnDefinition = "timestamptz")
    private Instant canceledAt;

    // 승인한 관리자 회원 ID·시각 (#294). 송금 단계로 넘어가도 지우지 않는다
    @Column(name = "approved_by", nullable = true)
    private Long approvedBy;

    @Column(name = "approved_at", nullable = true, columnDefinition = "timestamptz")
    private Instant approvedAt;

    // 거절한 관리자 회원 ID·시각·사유 (#294)
    @Column(name = "rejected_by", nullable = true)
    private Long rejectedBy;

    @Column(name = "rejected_at", nullable = true, columnDefinition = "timestamptz")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", nullable = true, length = 500)
    private String rejectionReason;

    // 최종 실패로 종료한 관리자 회원 ID·시각·사유 (#304)
    @Column(name = "final_failed_by", nullable = true)
    private Long finalFailedBy;

    @Column(name = "final_failed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant finalFailedAt;

    @Column(name = "final_failure_reason", nullable = true, length = 500)
    private String finalFailureReason;

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

    // 환전 신청(REQUESTED). 금액은 1,000 HP 이상·1,000 HP 단위이고, 수수료(10%, 원 미만 버림)와 예상 수령액(KRW)을 지금 확정한다.
    // 보류는 저장 후(환전 ID가 생긴 뒤) attachHold로 연결한다.
    public static Exchange requested(Long userId, Long walletId, long amountHp, String idempotencyKey,
            Instant provisionalPayoutAt, Instant requestedAt) {
        if (amountHp < UNIT_HP || amountHp % UNIT_HP != 0) {
            throw new BillingException(BillingErrorCode.EXCHANGE_AMOUNT_INVALID);
        }
        long feeHp = feeOf(amountHp);
        return Exchange.builder()
                .userId(userId)
                .walletId(walletId)
                .requestedAmountHp(amountHp)
                .feeRate(FEE_RATE)
                .feeAmountHp(feeHp)
                .payoutAmountKrw(amountHp - feeHp)
                .status(ExchangeStatus.REQUESTED)
                .idempotencyKey(idempotencyKey)
                .payoutScheduledAt(provisionalPayoutAt)
                .requestedAt(requestedAt)
                .build();
    }

    // 환전 수수료: 신청 금액 × 10%, 원 미만 버림
    public static long feeOf(long amountHp) {
        return BigDecimal.valueOf(amountHp).multiply(FEE_RATE).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    // 이 신청으로 만든 수익 HP 보류를 연결한다.
    public void attachHold(Long holdId) {
        if (status != ExchangeStatus.REQUESTED || pointHoldId != null) {
            throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        }
        this.pointHoldId = holdId;
    }

    // 관리자 승인 전(REQUESTED)인 신청을 취소한다. 그 밖의 상태(승인·송금 이후, 이미 끝남)는 EXCHANGE_NOT_CANCELABLE.
    // 이미 취소한 신청은 호출한 쪽이 isCanceled로 먼저 확인해 반환을 다시 하지 않는다.
    public void cancel(Instant canceledAt) {
        if (status != ExchangeStatus.REQUESTED) {
            throw new BillingException(BillingErrorCode.EXCHANGE_NOT_CANCELABLE);
        }
        this.status = ExchangeStatus.CANCELED;
        this.canceledAt = canceledAt;
    }

    public boolean isCanceled() {
        return status == ExchangeStatus.CANCELED;
    }

    // 이 관리자가 이미 승인한 신청인지(같은 관리자의 승인 재요청은 멱등 성공). 송금 단계로 넘어간 뒤의 재요청은 아니다
    public boolean isApprovedBy(Long adminId) {
        return status == ExchangeStatus.APPROVED && adminId.equals(approvedBy);
    }

    // 이 관리자가 같은 사유로 이미 거절한 신청인지(같은 관리자·같은 사유의 거절 재요청은 멱등 성공)
    public boolean isRejectedBy(Long adminId, String reason) {
        return status == ExchangeStatus.REJECTED && adminId.equals(rejectedBy) && reason.equals(rejectionReason);
    }

    // 관리자 승인 (#294): REQUESTED → APPROVED. 보류는 그대로 두고 잠정 정산 예정일도 바꾸지 않는다.
    // 그 밖의 상태(다른 관리자가 처리함·거절·취소·송금 단계)는 EXCHANGE_ALREADY_REVIEWED. 멱등 재요청은 호출한 쪽이 isApprovedBy로 먼저 거른다.
    public void approve(Long adminId, Instant approvedAt) {
        requireRequested();
        this.status = ExchangeStatus.APPROVED;
        this.approvedBy = adminId;
        this.approvedAt = approvedAt;
    }

    // 관리자 거절 (#294): REQUESTED → REJECTED. 사유는 앞뒤 공백을 뺀 값(1~500자)이어야 한다. 보류 반환은 호출한 쪽이 같은 트랜잭션에서 한다.
    public void reject(Long adminId, String reason, Instant rejectedAt) {
        requireRequested();
        this.status = ExchangeStatus.REJECTED;
        this.rejectedBy = adminId;
        this.rejectedAt = rejectedAt;
        this.rejectionReason = normalizeRejectionReason(reason);
    }

    // 거절 사유를 앞뒤 공백을 빼서 검사한다. 비었거나 500자를 넘으면 EXCHANGE_REJECTION_REASON_INVALID
    public static String normalizeRejectionReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        if (normalized.isEmpty() || normalized.length() > REJECTION_REASON_MAX) {
            throw new BillingException(BillingErrorCode.EXCHANGE_REJECTION_REASON_INVALID);
        }
        return normalized;
    }

    // 정산 예정 시각이 됐는지. 이 시각 전에는 승인된 환전도 송금하지 않는다 (#304)
    public boolean isPayoutDue(Instant now) {
        return !now.isBefore(payoutScheduledAt);
    }

    // 송금 결과를 기다리는 상태인지(PROCESSING·UNKNOWN). 이때는 새 송금 대신 마지막 시도의 결과를 확인한다 (#304)
    public boolean isPayoutInFlight() {
        return status == ExchangeStatus.PROCESSING || status == ExchangeStatus.UNKNOWN;
    }

    // 송금 시도 시작 (#304): APPROVED(첫 시도) 또는 FAILED(확정 실패 뒤 관리자 수동 재송금) → PROCESSING. 이전 실패 시각·사유는 지운다
    // (시도별 실패 기록은 ExchangePayout에 남는다). 승인 기록은 그대로 둔다. 그 밖의 상태는 EXCHANGE_NOT_PAYABLE
    public void startPayout() {
        if (status != ExchangeStatus.APPROVED && status != ExchangeStatus.FAILED) {
            throw new BillingException(BillingErrorCode.EXCHANGE_NOT_PAYABLE);
        }
        this.status = ExchangeStatus.PROCESSING;
        this.failedAt = null;
        this.failureReason = null;
    }

    // 송금 결과 대기 (#304): Mock 은행이 접수했고 결과는 아직이다
    public void payoutProcessing() {
        requirePayoutInFlight();
        this.status = ExchangeStatus.PROCESSING;
    }

    // 송금 결과 불명·운영자 확인 필요 (#304). 보류를 유지한다
    public void payoutUnknown() {
        requirePayoutInFlight();
        this.status = ExchangeStatus.UNKNOWN;
    }

    // 송금 성공 (#304). 보류 확정 차감은 호출한 쪽이 같은 트랜잭션에서 한다
    public void completePayout(Instant completedAt) {
        requirePayoutInFlight();
        this.status = ExchangeStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    // 마지막 송금 시도 확정 실패 (#304). 보류는 반환하지 않는다(다시 송금할 수 있다)
    public void failPayout(String reason, Instant failedAt) {
        requirePayoutInFlight();
        this.status = ExchangeStatus.FAILED;
        this.failedAt = failedAt;
        this.failureReason = reason;
    }

    // 관리자 최종 실패 확정 (#304): FAILED(마지막 시도 확정 실패)만 → FINAL_FAILED. 보류 반환은 호출한 쪽이 같은 트랜잭션에서 한다.
    // 마지막 시도의 실패 시각·사유는 그대로 둔다. 그 밖의 상태는 EXCHANGE_PAYOUT_NOT_FINALIZABLE. 멱등 재요청은 isFinalFailedBy로 먼저 거른다
    public void failFinally(Long adminId, String reason, Instant finalFailedAt) {
        if (status != ExchangeStatus.FAILED) {
            throw new BillingException(BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
        }
        this.status = ExchangeStatus.FINAL_FAILED;
        this.finalFailedBy = adminId;
        this.finalFailedAt = finalFailedAt;
        this.finalFailureReason = normalizeFinalFailureReason(reason);
    }

    // 이 관리자가 같은 사유로 이미 최종 실패 확정한 환전인지(같은 요청 재시도는 멱등 성공, 다시 반환하지 않는다)
    public boolean isFinalFailedBy(Long adminId, String reason) {
        return status == ExchangeStatus.FINAL_FAILED && adminId.equals(finalFailedBy) && reason.equals(finalFailureReason);
    }

    // 최종 실패 사유를 앞뒤 공백을 빼서 검사한다. 비었거나 500자를 넘으면 EXCHANGE_FINAL_FAILURE_REASON_INVALID
    public static String normalizeFinalFailureReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        if (normalized.isEmpty() || normalized.length() > REJECTION_REASON_MAX) {
            throw new BillingException(BillingErrorCode.EXCHANGE_FINAL_FAILURE_REASON_INVALID);
        }
        return normalized;
    }

    private void requirePayoutInFlight() {
        if (!isPayoutInFlight()) {
            throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        }
    }

    private void requireRequested() {
        if (status != ExchangeStatus.REQUESTED) {
            throw new BillingException(BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        }
    }
}
