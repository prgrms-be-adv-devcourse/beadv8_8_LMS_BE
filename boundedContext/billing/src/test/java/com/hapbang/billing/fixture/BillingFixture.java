package com.hapbang.billing.fixture;

import java.math.BigDecimal;
import java.time.Instant;

import com.hapbang.billing.domain.*;

/** 실제 PG 키·계좌정보를 사용하지 않는 영속성 테스트 데이터. */
public final class BillingFixture {

    // PostgreSQL 마이크로초 정밀도로 UTC 날짜 경계에 가까운 시각을 보존한다.
    public static final Instant NOW = Instant.parse("2026-10-12T23:59:59.123456Z");

    private BillingFixture() {
    }

    public static PointCharge.PointChargeBuilder charge(Wallet wallet) {
        return PointCharge.builder().walletId(wallet.getId())
                .amountHp(10_000L).paymentAmountKrw(10_000L).remainingPrincipalHp(6_000L)
                .pgProvider(PgProvider.TOSS_PAYMENTS).pgOrderId("fake-order")
                .status(PointChargeStatus.COMPLETED).requestedAt(NOW).completedAt(NOW);
    }

    public static PointHold.PointHoldBuilder hold(Wallet wallet) {
        return PointHold.builder().walletId(wallet.getId())
                .referenceType(BillingReferenceType.LECTURE_ORDER).referenceId(100L)
                .originalChargeHp(4_000L).originalRevenueHp(1_000L)
                .heldChargeHp(4_000L).heldRevenueHp(1_000L)
                .status(PointHoldStatus.HELD).idempotencyKey("hold-100").heldAt(NOW);
    }

    public static PointLedger.PointLedgerBuilder ledger(Wallet wallet) {
        return PointLedger.builder().walletId(wallet.getId()).pointType(PointType.CHARGE)
                .balanceBucket(BalanceBucket.AVAILABLE).amountHp(-4_000L)
                .transactionType(LedgerTransactionType.HOLD)
                .referenceType(BillingReferenceType.LECTURE_ORDER).referenceId(100L)
                .operationKey("HOLD:100").idempotencyKey("HOLD:100:1").occurredAt(NOW);
    }

    public static PointHoldDetail.PointHoldDetailBuilder detail(PointHold hold) {
        return PointHoldDetail.builder().pointHold(hold)
                .referenceType(BillingReferenceType.LECTURE_ORDER_ITEM).referenceId(101L)
                .actionType(HoldDetailActionType.RELEASE)
                .chargeAmountHp(1_000L).revenueAmountHp(0L).idempotencyKey("release-101");
    }

    public static PointChargeUsage.PointChargeUsageBuilder usage(PointCharge charge, PointHold hold) {
        return PointChargeUsage.builder().chargeId(charge.getId()).pointHold(hold)
                .amountHp(4_000L).remainingHeldHp(4_000L).consumedHp(0L).releasedHp(0L)
                .status(PointChargeUsageStatus.HELD);
    }

    public static PointRefundRequest.PointRefundRequestBuilder refundRequest(Wallet wallet) {
        return PointRefundRequest.builder().walletId(wallet.getId())
                .requestedAmountHp(2_000L).completedAmountHp(0L)
                .status(PointRefundRequestStatus.PENDING)
                .idempotencyKey("refund-request-1").requestedAt(NOW);
    }

    public static PointRefund.PointRefundBuilder refund(
            Wallet wallet, PointCharge charge, PointRefundRequest request) {
        return PointRefund.builder().walletId(wallet.getId()).chargeId(charge.getId()).refundRequest(request)
                .refundAmountHp(1_000L).refundAmountKrw(1_000L)
                .status(PointRefundStatus.PENDING).idempotencyKey("refund-1").requestedAt(NOW);
    }

    public static Settlement.SettlementBuilder settlement(Wallet receiver, PointHold hold) {
        return Settlement.builder().receiverUserId(receiver.getUserId()).receiverWalletId(receiver.getId())
                .pointHoldId(hold.getId()).sourceType(SettlementSourceType.LECTURE_ORDER_ITEM).sourceId(101L)
                .payoutType(PayoutType.NORMAL).grossAmountHp(2_000L).paidAmountHp(0L)
                .status(SettlementStatus.SCHEDULED).idempotencyKey("settlement-101")
                .scheduledAt(NOW.plusSeconds(86_400));
    }

    public static Exchange.ExchangeBuilder exchange(Wallet wallet) {
        return Exchange.builder().userId(wallet.getUserId()).walletId(wallet.getId())
                .requestedAmountHp(1_000L).feeRate(new BigDecimal("0.1000"))
                .feeAmountHp(100L).payoutAmountKrw(900L).status(ExchangeStatus.REQUESTED)
                .idempotencyKey("exchange-1").requestedAt(NOW)
                .payoutScheduledAt(NOW.plusSeconds(86_400));
    }

    public static ExchangeAccount.ExchangeAccountBuilder account(Exchange exchange) {
        return ExchangeAccount.builder().exchange(exchange)
                .verificationStatus(AccountVerificationStatus.PENDING);
    }

    public static ExchangePayout.ExchangePayoutBuilder payout(Exchange exchange) {
        return ExchangePayout.builder().exchange(exchange).payoutProvider(PayoutProvider.MOCK)
                .payoutRequestId("mock-request-1").amountKrw(900L)
                .status(ExchangePayoutStatus.PENDING).requestedAt(NOW);
    }
}

