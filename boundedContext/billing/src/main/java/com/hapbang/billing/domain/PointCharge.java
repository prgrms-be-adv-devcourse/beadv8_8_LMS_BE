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
                @UniqueConstraint(name = "uk_point_charge_pg_payment_key", columnNames = {"pg_payment_key"})
        },
        indexes = {
                @Index(name = "idx_point_charge_1", columnList = "wallet_id, status, completed_at, id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(amount_hp >= 1000 AND amount_hp % 1000 = 0) AND (payment_amount_krw > 0) AND (remaining_principal_hp >= 0 AND remaining_principal_hp <= amount_hp)")
public class PointCharge extends BillingUpdatedEntity {

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "amount_hp", nullable = false)
    private Long amountHp;

    @Column(name = "payment_amount_krw", nullable = false)
    private Long paymentAmountKrw;

    @Column(name = "remaining_principal_hp", nullable = false)
    private Long remainingPrincipalHp;

    @Enumerated(EnumType.STRING)
    @Column(name = "pg_provider", nullable = false)
    private PgProvider pgProvider;

    @Column(name = "pg_order_id", nullable = false)
    private String pgOrderId;

    // 암호문 저장용 자리만 준비한다. 실제 키 저장·암호화·원문 중복 검증은 PG 연동에서 구현한다.
    @Column(name = "pg_payment_key", nullable = true)
    private String pgPaymentKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PointChargeStatus status;

    @Column(name = "requested_at", nullable = false, columnDefinition = "timestamptz")
    private Instant requestedAt;

    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant completedAt;

    @Column(name = "failed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant failedAt;

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
        if (amountHp > this.amountHp - remainingPrincipalHp) {
            throw new BillingException(BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        }
        remainingPrincipalHp += amountHp;
    }
}
