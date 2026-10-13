package com.hapbang.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 환전별 가상 송금 요청 식별자, KRW 금액과 처리 결과를 보관한다.
@Entity
@Table(name = "exchange_payout", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_exchange_payout_exchange_id", columnNames = {"exchange_id"}),
                @UniqueConstraint(name = "uk_exchange_payout_payout_request_id", columnNames = {"payout_request_id"}),
                @UniqueConstraint(name = "uk_exchange_payout_payout_transaction_id", columnNames = {"payout_transaction_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(amount_krw >= 0)")
public class ExchangePayout extends BillingCreatedEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exchange_id", nullable = false, unique = true)
    private Exchange exchange;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_provider", nullable = false)
    private PayoutProvider payoutProvider;

    @Column(name = "payout_request_id", nullable = false)
    private String payoutRequestId;

    @Column(name = "payout_transaction_id", nullable = true)
    private String payoutTransactionId;

    @Column(name = "amount_krw", nullable = false)
    private Long amountKrw;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExchangePayoutStatus status;

    @Column(name = "requested_at", nullable = false, columnDefinition = "timestamptz")
    private Instant requestedAt;

    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant completedAt;

    @Column(name = "failed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant failedAt;

    @Column(name = "failure_reason", nullable = true, columnDefinition = "text")
    private String failureReason;

    // 빌더로 전달받은 환전·송금 제공자·요청 식별자·금액·처리 결과로 송금 객체를 초기화한다.
    @Builder
    private ExchangePayout(
            Exchange exchange,
            PayoutProvider payoutProvider,
            String payoutRequestId,
            String payoutTransactionId,
            Long amountKrw,
            ExchangePayoutStatus status,
            Instant requestedAt,
            Instant completedAt,
            Instant failedAt,
            String failureReason
    ) {
        this.exchange = exchange;
        this.payoutProvider = payoutProvider;
        this.payoutRequestId = payoutRequestId;
        this.payoutTransactionId = payoutTransactionId;
        this.amountKrw = amountKrw;
        this.status = status;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
        this.failureReason = failureReason;
    }
}
