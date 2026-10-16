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

// 환전의 가상 송금 시도 하나(지급 요청 ID, KRW 금액, 처리 결과)를 보관한다 (#304).
// - 환전 1건에 시도 여러 건(attempt_no 1부터). 확정 실패(FAILED) 뒤 다시 보내면 새 지급 요청 ID의 새 시도다. 결과를 모르는 시도
//   (PROCESSING·UNKNOWN)는 새 ID로 다시 보내지 않고 같은 ID로 조회·재전송한다(#288 계약).
// - DB가 환전마다 FAILED가 아닌 시도를 최대 1건으로 막는다(uk_exchange_payout_active): 결과를 모르는 동안 새 시도를 만들 수 없고
//   성공한 환전은 다시 보낼 수 없다.
// - 송금(HTTP) 전에 PROCESSING으로 저장·커밋한다. 그 뒤 어느 단계가 실패해도 같은 지급 요청 ID로 결과를 확인할 수 있다.
// - Mock 은행 기록(mock_payout_transfer)과 따로 남는다(서로 대신하지 않는다).
@Entity
@Table(name = "exchange_payout", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_exchange_payout_exchange_id_attempt_no",
                        columnNames = {"exchange_id", "attempt_no"}),
                @UniqueConstraint(name = "uk_exchange_payout_payout_request_id", columnNames = {"payout_request_id"}),
                @UniqueConstraint(name = "uk_exchange_payout_payout_transaction_id", columnNames = {"payout_transaction_id"})
        },
        indexes = {
                @Index(name = "idx_exchange_payout_1", columnList = "status, last_checked_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(amount_krw >= 0)")
public class ExchangePayout extends BillingCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exchange_id", nullable = false)
    private Exchange exchange;

    // 환전 안의 송금 시도 번호(1부터)
    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;

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

    // 확정 실패면 Mock 은행 실패 코드, 결과 불명·운영자 확인이면 마지막으로 남긴 사유(계좌번호·접근 키는 넣지 않는다)
    @Column(name = "failure_reason", nullable = true, columnDefinition = "text")
    private String failureReason;

    // 마지막으로 송금·자동 결과 확인을 시작한 시각. 자동 결과 확인은 이 시각이 일정 시간 지난 시도만 한다(다른 실행과 겹치지 않게)
    @Column(name = "last_checked_at", nullable = false, columnDefinition = "timestamptz")
    private Instant lastCheckedAt;

    // 자동 결과 확인(같은 지급 요청 ID 조회)을 시작한 횟수. 처음 송금·관리자 재확인은 세지 않는다
    @Column(name = "result_check_count", nullable = false)
    private Integer resultCheckCount;

    // 운영자 확인(MANUAL_REVIEW_REQUIRED)으로 처음 넘어간 시각. 이후 결과가 확정돼도 지우지 않는다
    @Column(name = "manual_review_at", nullable = true, columnDefinition = "timestamptz")
    private Instant manualReviewAt;

    // 이 시도를 보낸 관리자 회원 ID(확정 실패 뒤 관리자 수동 재송금). 자동 송금은 null
    @Column(name = "requested_by", nullable = true)
    private Long requestedBy;

    // 마지막으로 같은 지급 요청 ID로 결과를 다시 확인한 관리자·시각
    @Column(name = "manual_checked_by", nullable = true)
    private Long manualCheckedBy;

    @Column(name = "manual_checked_at", nullable = true, columnDefinition = "timestamptz")
    private Instant manualCheckedAt;

    // 빌더로 전달받은 환전·송금 제공자·요청 식별자·금액·처리 결과로 송금 객체를 초기화한다.
    @Builder
    private ExchangePayout(
            Exchange exchange,
            Integer attemptNo,
            PayoutProvider payoutProvider,
            String payoutRequestId,
            String payoutTransactionId,
            Long amountKrw,
            ExchangePayoutStatus status,
            Instant requestedAt,
            Instant completedAt,
            Instant failedAt,
            String failureReason,
            Instant lastCheckedAt,
            Long requestedBy
    ) {
        this.exchange = exchange;
        this.attemptNo = attemptNo;
        this.payoutProvider = payoutProvider;
        this.payoutRequestId = payoutRequestId;
        this.payoutTransactionId = payoutTransactionId;
        this.amountKrw = amountKrw;
        this.status = status;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
        this.failureReason = failureReason;
        this.lastCheckedAt = lastCheckedAt == null ? requestedAt : lastCheckedAt;
        this.resultCheckCount = 0;
        this.requestedBy = requestedBy;
    }

    // 새 송금 시도. 송금(HTTP) 전에 PROCESSING으로 저장한다. 금액은 환전 예상 수령액(신청 금액 - 수수료)이다.
    // requestedBy는 관리자 수동 재송금이면 그 관리자, 자동 송금이면 null
    public static ExchangePayout requested(Exchange exchange, int attemptNo, String payoutRequestId, Instant now,
            Long requestedBy) {
        return ExchangePayout.builder()
                .exchange(exchange)
                .attemptNo(attemptNo)
                .payoutProvider(PayoutProvider.MOCK)
                .payoutRequestId(payoutRequestId)
                .amountKrw(exchange.getPayoutAmountKrw())
                .status(ExchangePayoutStatus.PROCESSING)
                .requestedAt(now)
                .lastCheckedAt(now)
                .requestedBy(requestedBy)
                .build();
    }

    // 결과를 아직 모르는 시도(자동 결과 확인·재전송 대상)
    public boolean isInFlight() {
        return status == ExchangePayoutStatus.PROCESSING || status == ExchangePayoutStatus.UNKNOWN;
    }

    // 결과가 아직 확정되지 않은 시도(결과 대기 + 운영자 확인). 관리자 재확인 대상
    public boolean isUnresolved() {
        return isInFlight() || status == ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED;
    }

    // 최초 송금 요청 후 자동 결과 확인 기한이 지났는지
    public boolean isAutoCheckExpired(Instant now, Duration limit) {
        return !now.isBefore(requestedAt.plus(limit));
    }

    // 자동 결과 확인을 시작한다(다른 실행이 일정 시간 같은 시도를 확인하지 않게). 확인 횟수를 센다
    public void recordAutoCheck(Instant now) {
        requireInFlight();
        this.lastCheckedAt = now;
        this.resultCheckCount = resultCheckCount + 1;
    }

    // 관리자가 같은 지급 요청 ID로 결과를 다시 확인했다(자동 확인 시각·횟수는 바꾸지 않는다)
    public void recordManualCheck(Long adminId, Instant now) {
        requireUnresolved();
        this.manualCheckedBy = adminId;
        this.manualCheckedAt = now;
    }

    // Mock 은행이 접수했고 결과는 아직이다
    public void processing() {
        requireInFlight();
        this.status = ExchangePayoutStatus.PROCESSING;
        this.failureReason = null;
    }

    // 결과 불명(시간 초과·연결 실패·5xx 등). 실패가 아니다
    public void unknown(String reason) {
        requireInFlight();
        this.status = ExchangePayoutStatus.UNKNOWN;
        this.failureReason = reason;
    }

    // 송금 성공(결과 대기 또는 운영자 확인 중인 시도의 결과가 성공으로 확인됨)
    public void succeed(String transactionId, Instant completedAt) {
        requireUnresolved();
        if (transactionId == null || transactionId.isBlank()) {
            throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        }
        this.status = ExchangePayoutStatus.COMPLETED;
        this.payoutTransactionId = transactionId;
        this.completedAt = completedAt;
        this.failureReason = null;
    }

    // 송금 확정 실패(은행이 실패로 확정했거나 운영자 확인 중 은행에 기록이 없음이 확인됨)
    public void fail(String failureCode, Instant failedAt) {
        requireUnresolved();
        this.status = ExchangePayoutStatus.FAILED;
        this.failedAt = failedAt;
        this.failureReason = failureCode;
    }

    // 자동 처리할 수 없다(409·그 밖의 4xx 거절, 성공 응답 불일치, 자동 확인 기한 초과). 자동 결과 확인 대상에서 빠진다.
    // 처음 넘어간 시각만 남기고 사유는 마지막 값으로 바꾼다
    public void requireManualReview(String reason, Instant now) {
        requireUnresolved();
        this.status = ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED;
        this.failureReason = reason;
        if (manualReviewAt == null) {
            this.manualReviewAt = now;
        }
    }

    private void requireInFlight() {
        if (!isInFlight()) {
            throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        }
    }

    private void requireUnresolved() {
        if (!isUnresolved()) {
            throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        }
    }
}
