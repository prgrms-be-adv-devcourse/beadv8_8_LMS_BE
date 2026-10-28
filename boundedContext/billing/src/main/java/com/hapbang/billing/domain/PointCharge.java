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

// PG 충전 요청·결과와 해당 충전 건에서 사용·보류·환불되지 않은 가용 원금을 보관한다.
@Entity
@Table(name = "point_charge", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_point_charge_pg_order_id", columnNames = {"pg_order_id"}),
                @UniqueConstraint(name = "uk_point_charge_pg_payment_key", columnNames = {"pg_payment_key"}),
                @UniqueConstraint(name = "uk_point_charge_pg_payment_key_hash", columnNames = {"pg_payment_key_hash"})
        },
        indexes = {
                @Index(name = "idx_point_charge_1", columnList = "wallet_id, status, completed_at, id"),
                @Index(name = "idx_point_charge_2", columnList = "status, updated_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(amount_hp >= 1000 AND amount_hp % 1000 = 0) AND (payment_amount_krw > 0) AND (remaining_principal_hp >= 0 AND remaining_principal_hp <= amount_hp) AND (refunded_amount_hp >= 0 AND refunded_amount_hp + remaining_principal_hp <= amount_hp)")
public class PointCharge extends BillingUpdatedEntity {

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "amount_hp", nullable = false)
    private Long amountHp;

    @Column(name = "payment_amount_krw", nullable = false)
    private Long paymentAmountKrw;

    @Column(name = "remaining_principal_hp", nullable = false)
    private Long remainingPrincipalHp;

    // PG 취소로 환불을 마친 금액의 누계 (#260). 환불 보류에 배분되며 이미 가용 원금에서 빠진 금액이다.
    // 가용 원금 + 환불 누계 ≤ 충전 금액(ck_point_charge_4)이라 같은 원금을 두 번 환불하지 않는다.
    @Column(name = "refunded_amount_hp", nullable = false)
    private long refundedAmountHp;

    @Enumerated(EnumType.STRING)
    @Column(name = "pg_provider", nullable = false)
    private PgProvider pgProvider;

    @Column(name = "pg_order_id", nullable = false)
    private String pgOrderId;

    // PG 결제키의 암호문(AES-GCM, 무작위 IV)이다. 평문은 저장하지 않는다 (#224).
    @Column(name = "pg_payment_key", nullable = true, length = 512)
    private String pgPaymentKey;

    // 결제키의 HMAC-SHA256(hex). 암호문은 매번 달라 중복을 가리지 못하므로 이 값의 UNIQUE로 같은 결제의 중복 사용을 막는다 (#224).
    @Column(name = "pg_payment_key_hash", nullable = true, length = 64)
    private String pgPaymentKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PointChargeStatus status;

    @Column(name = "requested_at", nullable = false, columnDefinition = "timestamptz")
    private Instant requestedAt;

    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant completedAt;

    @Column(name = "failed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant failedAt;

    // 승인 실패 사유(PG 오류 코드·메시지), 또는 결과를 확정하지 못한 PROCESSING 건의 마지막 확인 사유(운영자 확인용) (#224).
    @Column(name = "failure_reason", nullable = true, columnDefinition = "text")
    private String failureReason;

    // 빌더로 전달받은 지갑·충전 금액·가용 원금·PG 식별자·처리 상태와 시각으로 충전 객체를 초기화한다.
    @Builder
    private PointCharge(
            Long walletId,
            Long amountHp,
            Long paymentAmountKrw,
            Long remainingPrincipalHp,
            PgProvider pgProvider,
            String pgOrderId,
            String pgPaymentKey,
            PointChargeStatus status,
            Instant requestedAt,
            Instant completedAt,
            Instant failedAt
    ) {
        this.walletId = walletId;
        this.amountHp = amountHp;
        this.paymentAmountKrw = paymentAmountKrw;
        this.remainingPrincipalHp = remainingPrincipalHp;
        this.pgProvider = pgProvider;
        this.pgOrderId = pgOrderId;
        this.pgPaymentKey = pgPaymentKey;
        this.status = status;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
    }

    // 이 충전 건의 가용 원금에서 보류에 배분한 금액을 뺀다. 0 이하이면 INVALID_POINT_AMOUNT, 가용 원금을 넘으면 CHARGE_PRINCIPAL_MISMATCH로 거절한다.
    public void reservePrincipal(long amountHp) {
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        if (amountHp > remainingPrincipalHp) {
            throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
        remainingPrincipalHp -= amountHp;
    }

    // 보류에 배분했던 원금을 반환받아 이 충전 건의 가용 원금에 되돌린다. 0 이하이면 INVALID_POINT_AMOUNT로 거절한다.
    // 가용 원금으로 셀 수 없는 상태(완료·부분 환불이 아님)이거나 충전 금액을 넘게 되면 원금 기록이 어긋난 것이므로 CHARGE_PRINCIPAL_MISMATCH로 거절한다.
    public void restorePrincipal(long amountHp) {
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        if (status != PointChargeStatus.COMPLETED && status != PointChargeStatus.PARTIALLY_REFUNDED) {
            throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
        // 환불을 마친 원금은 보류에 배분된 원금이 아니므로 되돌려 받을 수 있는 한도에서 뺀다 (#260)
        if (amountHp > this.amountHp - remainingPrincipalHp - refundedAmountHp) {
            throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
        remainingPrincipalHp += amountHp;
    }

    // PG 취소 성공으로 환불 보류에서 확정 차감한 금액을 환불 누계에 더한다 (#260). 가용 원금은 보류할 때 이미 줄었으므로 바꾸지 않는다.
    // 충전 금액 전부를 환불하면 REFUNDED, 아니면 PARTIALLY_REFUNDED다. 보류에 배분되지 않은 원금까지 환불하게 되면 CHARGE_PRINCIPAL_MISMATCH다.
    public void refund(long amountHp) {
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        if (status != PointChargeStatus.COMPLETED && status != PointChargeStatus.PARTIALLY_REFUNDED) {
            throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
        if (amountHp > this.amountHp - remainingPrincipalHp - refundedAmountHp) {
            throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
        refundedAmountHp += amountHp;
        status = refundedAmountHp == this.amountHp ? PointChargeStatus.REFUNDED : PointChargeStatus.PARTIALLY_REFUNDED;
    }

    // 결제 전 충전 주문을 만든다(PENDING). 1 HP = 1원이고, 승인 전이라 가용 원금은 0이다 (#224).
    public static PointCharge requested(Long walletId, long amountHp, String pgOrderId, Instant requestedAt) {
        return PointCharge.builder()
                .walletId(walletId)
                .amountHp(amountHp)
                .paymentAmountKrw(amountHp)
                .remainingPrincipalHp(0L)
                .pgProvider(PgProvider.TOSS_PAYMENTS)
                .pgOrderId(pgOrderId)
                .status(PointChargeStatus.PENDING)
                .requestedAt(requestedAt)
                .build();
    }

    // PG 승인 요청을 시작한다(PENDING → PROCESSING). 결제키 암호문과 HMAC을 이때 한 번만 기록한다.
    public void startConfirm(String encryptedPaymentKey, String paymentKeyHash) {
        requireStatus(PointChargeStatus.PENDING);
        this.pgPaymentKey = encryptedPaymentKey;
        this.pgPaymentKeyHash = paymentKeyHash;
        this.status = PointChargeStatus.PROCESSING;
    }

    // PG 승인이 확인되어 충전을 확정한다(PROCESSING → COMPLETED). 충전 금액 전부가 가용 원금이 된다.
    // 지갑 충전 HP 증가는 같은 트랜잭션의 충전 원장으로만 한다.
    public void complete(Instant completedAt) {
        requireStatus(PointChargeStatus.PROCESSING);
        this.remainingPrincipalHp = amountHp;
        this.status = PointChargeStatus.COMPLETED;
        this.completedAt = completedAt;
        this.failureReason = null;
    }

    // PG가 승인하지 않은 것이 확정되어 실패로 끝낸다(PROCESSING → FAILED). HP는 바뀌지 않는다.
    public void fail(String reason, Instant failedAt) {
        requireStatus(PointChargeStatus.PROCESSING);
        this.status = PointChargeStatus.FAILED;
        this.failedAt = failedAt;
        this.failureReason = reason;
    }

    // 결과를 아직 확정하지 못한 PROCESSING 건에 마지막 확인 사유를 남긴다(상태는 그대로, 결과 확인 배치·재요청이 다시 확인한다).
    public void noteUnresolved(String reason) {
        requireStatus(PointChargeStatus.PROCESSING);
        this.failureReason = reason;
    }

    // 결제키 HMAC이 이 충전 건에 기록된 값과 같은지 확인한다.
    public boolean hasPaymentKeyHash(String paymentKeyHash) {
        return pgPaymentKeyHash != null && pgPaymentKeyHash.equals(paymentKeyHash);
    }

    // 충전이 확정되어 HP가 들어간 상태(완료·부분 환불·전부 환불)인지 확인한다.
    public boolean isSettled() {
        return status == PointChargeStatus.COMPLETED || status == PointChargeStatus.PARTIALLY_REFUNDED
                || status == PointChargeStatus.REFUNDED;
    }

    // 상태 전이 전제가 깨지면 호출 순서가 잘못된 것이므로 IllegalStateException으로 알린다.
    private void requireStatus(PointChargeStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("충전 상태가 " + expected + "가 아닙니다. chargeId=" + getId()
                    + ", status=" + status);
        }
    }
}
