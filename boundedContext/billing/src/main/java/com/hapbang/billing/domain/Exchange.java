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
// 회원 취소는 관리자 승인 전(REQUESTED)에만 할 수 있다. 승인·송금 상태 전이는 후속 Issue에서 더한다.
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
}
