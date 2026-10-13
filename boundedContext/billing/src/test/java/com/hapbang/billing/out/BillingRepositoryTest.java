package com.hapbang.billing.out;

import static com.hapbang.billing.fixture.BillingFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.billing.domain.*;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class BillingRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PointLedgerRepository pointLedgerRepository;

    @Autowired
    private PointHoldRepository pointHoldRepository;

    @Autowired
    private PointHoldDetailRepository pointHoldDetailRepository;

    @Autowired
    private PointChargeRepository pointChargeRepository;

    @Autowired
    private PointChargeUsageRepository pointChargeUsageRepository;

    @Autowired
    private PointRefundRequestRepository pointRefundRequestRepository;

    @Autowired
    private PointRefundRepository pointRefundRepository;

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private ExchangeRepository exchangeRepository;

    @Autowired
    private ExchangeAccountRepository exchangeAccountRepository;

    @Autowired
    private ExchangePayoutRepository exchangePayoutRepository;

    @Test
    void 모든_엔티티를_저장하고_영속성_컨텍스트를_비운_뒤_조회한다() {
        Instant before = Instant.now();
        Wallet wallet = walletRepository.save(Wallet.create(10L));
        PointCharge charge = pointChargeRepository.save(charge(wallet).pgPaymentKey("fake-payment-key").build());
        PointHold hold = pointHoldRepository.save(hold(wallet).build());
        PointLedger ledger = pointLedgerRepository.save(ledger(wallet).build());
        PointHoldDetail detail = pointHoldDetailRepository.save(detail(hold).build());
        PointChargeUsage usage = pointChargeUsageRepository.save(usage(charge, hold).build());
        PointRefundRequest request = pointRefundRequestRepository.save(refundRequest(wallet).build());
        PointRefund refund = pointRefundRepository.save(refund(wallet, charge, request).build());
        Settlement settlement = settlementRepository.save(settlement(wallet, hold).build());
        Exchange exchange = exchangeRepository.save(exchange(wallet).build());
        ExchangeAccount account = exchangeAccountRepository.save(account(exchange).build());
        ExchangePayout payout = exchangePayoutRepository.save(payout(exchange).build());
        entityManager.flush();
        entityManager.clear();

        Wallet foundWallet = walletRepository.findByUserId(10L).orElseThrow();
        assertThat(foundWallet.getId()).isEqualTo(wallet.getId());
        assertThat(List.of(foundWallet.getChargeBalanceHp(), foundWallet.getRevenueBalanceHp(),
                foundWallet.getHeldChargeHp(), foundWallet.getHeldRevenueHp())).containsOnly(0L);
        assertThat(foundWallet.getCreatedAt()).isBetween(before.minusNanos(1_000), Instant.now().plusNanos(1_000));
        assertThat(foundWallet.getUpdatedAt()).isEqualTo(foundWallet.getCreatedAt());

        PointCharge foundCharge = pointChargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(foundCharge.getWalletId()).isEqualTo(wallet.getId());
        assertThat(foundCharge.getPgPaymentKey()).isEqualTo("fake-payment-key");
        assertThat(foundCharge.getRemainingPrincipalHp()).isEqualTo(6_000L);
        assertThat(foundCharge.getStatus()).isEqualTo(PointChargeStatus.COMPLETED);

        PointHold foundHold = pointHoldRepository.findById(hold.getId()).orElseThrow();
        assertThat(foundHold.getOriginalChargeHp()).isEqualTo(4_000L);
        assertThat(foundHold.getHeldRevenueHp()).isEqualTo(1_000L);
        assertThat(foundHold.getReferenceType()).isEqualTo(BillingReferenceType.LECTURE_ORDER);
        assertThat(pointLedgerRepository.findById(ledger.getId()).orElseThrow().getAmountHp()).isEqualTo(-4_000L);
        assertThat(pointHoldDetailRepository.findById(detail.getId()).orElseThrow().getActionType())
                .isEqualTo(HoldDetailActionType.RELEASE);
        assertThat(pointChargeUsageRepository.findById(usage.getId()).orElseThrow().getChargeId())
                .isEqualTo(charge.getId());
        assertThat(pointRefundRequestRepository.findById(request.getId()).orElseThrow().getPointHoldId()).isNull();
        assertThat(pointRefundRepository.findById(refund.getId()).orElseThrow().getRefundRequest().getId())
                .isEqualTo(request.getId());
        assertThat(settlementRepository.findById(settlement.getId()).orElseThrow().getSourceType())
                .isEqualTo(SettlementSourceType.LECTURE_ORDER_ITEM);
        Exchange foundExchange = exchangeRepository.findById(exchange.getId()).orElseThrow();
        assertThat(foundExchange.getPointHoldId()).isNull();
        assertThat(foundExchange.getFeeRate()).isEqualByComparingTo(new BigDecimal("0.1000"));
        assertThat(exchangeAccountRepository.findById(account.getId()).orElseThrow().getAccountNumberEncrypted()).isNull();
        assertThat(exchangePayoutRepository.findById(payout.getId()).orElseThrow().getPayoutProvider())
                .isEqualTo(PayoutProvider.MOCK);
    }

    @Test
    void 하나의_환불_요청에_여러_충전건의_환불을_연결한다() {
        Wallet wallet = walletRepository.save(Wallet.create(20L));
        PointCharge first = pointChargeRepository.save(charge(wallet).build());
        PointCharge second = pointChargeRepository.save(charge(wallet).pgOrderId("fake-order-2").build());
        PointRefundRequest request = pointRefundRequestRepository.save(refundRequest(wallet).build());
        PointRefund firstRefund = pointRefundRepository.save(refund(wallet, first, request).build());
        PointRefund secondRefund = pointRefundRepository.save(
                refund(wallet, second, request).idempotencyKey("refund-2").build());
        entityManager.flush();
        entityManager.clear();

        List<PointRefund> refunds = pointRefundRepository.findAllByRefundRequestIdOrderByIdAsc(request.getId());
        assertThat(refunds).extracting(PointRefund::getId).containsExactly(firstRefund.getId(), secondRefund.getId());
        assertThat(refunds).extracting(PointRefund::getChargeId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void 환전은_보류와_계좌와_송금행_없이_먼저_생성할_수_있다() {
        Wallet wallet = walletRepository.save(Wallet.create(30L));
        Exchange exchange = exchangeRepository.save(exchange(wallet).build());
        entityManager.flush();
        entityManager.clear();

        Exchange found = exchangeRepository.findById(exchange.getId()).orElseThrow();
        assertThat(found.getPointHoldId()).isNull();
        assertThat(exchangeAccountRepository.findByExchangeId(exchange.getId())).isEmpty();
        assertThat(exchangePayoutRepository.findByExchangeId(exchange.getId())).isEmpty();

        ExchangeAccount account = exchangeAccountRepository.save(account(found).build());
        ExchangePayout payout = exchangePayoutRepository.save(payout(found).build());
        entityManager.flush();
        entityManager.clear();

        assertThat(exchangeAccountRepository.findByExchangeId(exchange.getId()).orElseThrow().getId())
                .isEqualTo(account.getId());
        assertThat(exchangePayoutRepository.findByExchangeId(exchange.getId()).orElseThrow().getId())
                .isEqualTo(payout.getId());
    }

    @Test
    void 같은_작업의_여러_원장항목과_역분개를_각각_보존한다() {
        Wallet wallet = walletRepository.save(Wallet.create(40L));
        PointLedger first = pointLedgerRepository.save(ledger(wallet).build());
        pointLedgerRepository.save(ledger(wallet).balanceBucket(BalanceBucket.HELD)
                .amountHp(4_000L).idempotencyKey("HOLD:100:2").build());
        PointLedger reversal = pointLedgerRepository.save(ledger(wallet).amountHp(4_000L)
                .transactionType(LedgerTransactionType.REVERSAL).originalLedgerId(first.getId())
                .operationKey("REVERSAL:100").idempotencyKey("REVERSAL:100:1").build());
        entityManager.flush();
        entityManager.clear();

        assertThat(pointLedgerRepository.findAllByOperationKeyOrderByIdAsc("HOLD:100"))
                .extracting(PointLedger::getAmountHp).containsExactly(-4_000L, 4_000L);
        assertThat(pointLedgerRepository.findById(reversal.getId()).orElseThrow().getOriginalLedgerId())
                .isEqualTo(first.getId());
        assertThat(pointLedgerRepository.findByIdempotencyKey("HOLD:100:1").orElseThrow().getAmountHp())
                .isEqualTo(-4_000L);
    }

    @Test
    void Instant는_DB_세션_시간대가_달라도_같은_순간을_보존한다() {
        Wallet wallet = walletRepository.save(Wallet.create(50L));
        PointLedger ledger = pointLedgerRepository.save(ledger(wallet).build());
        entityManager.flush();
        entityManager.clear();
        entityManager.createNativeQuery("SET LOCAL TIME ZONE 'Pacific/Honolulu'").executeUpdate();

        assertThat(pointLedgerRepository.findById(ledger.getId()).orElseThrow().getOccurredAt()).isEqualTo(NOW);
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT point_type, balance_bucket, transaction_type, reference_type,
                       pg_typeof(occurred_at)::text
                FROM billing.point_ledger WHERE id = :id
                """).setParameter("id", ledger.getId()).getSingleResult();
        assertThat(row).containsExactly("CHARGE", "AVAILABLE", "HOLD", "LECTURE_ORDER", "timestamp with time zone");
    }

    @Test
    void 수정_감사시각은_갱신하고_생성시각은_보존한다() {
        Wallet wallet = walletRepository.save(Wallet.create(60L));
        PointCharge charge = pointChargeRepository.save(charge(wallet).build());
        entityManager.flush();
        Instant createdAt = charge.getCreatedAt();
        Instant previousUpdate = charge.getUpdatedAt();
        // 거래 메서드를 추가하지 않고 영속성 콜백 자체를 검증한다.
        ReflectionTestUtils.setField(charge, "pgOrderId", "fake-order-updated");
        entityManager.flush();
        entityManager.clear();

        PointCharge found = pointChargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(found.getCreatedAt()).isEqualTo(createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(found.getUpdatedAt()).isAfterOrEqualTo(previousUpdate.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void ID로만_참조해도_없는_지갑_충전건_원본원장을_가리키면_DB_FK가_거절한다() {
        Wallet wallet = walletRepository.save(Wallet.create(50L));
        PointCharge charge = pointChargeRepository.save(charge(wallet).build());
        PointHold hold = pointHoldRepository.save(hold(wallet).build());
        long missing = Long.MAX_VALUE;

        assertForeignKeyViolation(() -> pointLedgerRepository.save(ledger(wallet).walletId(missing)
                .operationKey("HOLD:missing").idempotencyKey("HOLD:missing:1").build()), "fk_point_ledger_wallet_id");
        assertForeignKeyViolation(() -> pointChargeRepository.save(charge(wallet).walletId(missing)
                .pgOrderId("fake-order-missing").build()), "fk_point_charge_wallet_id");
        assertForeignKeyViolation(() -> pointHoldRepository.save(hold(wallet).walletId(missing)
                .idempotencyKey("hold-missing").build()), "fk_point_hold_wallet_id");
        assertForeignKeyViolation(() -> pointChargeUsageRepository.save(usage(charge, hold).chargeId(missing).build()),
                "fk_point_charge_usage_charge_id");
        assertForeignKeyViolation(() -> pointLedgerRepository.save(ledger(wallet).amountHp(4_000L)
                .transactionType(LedgerTransactionType.REVERSAL).originalLedgerId(missing)
                .operationKey("REVERSAL:missing").idempotencyKey("REVERSAL:missing:1").build()),
                "fk_point_ledger_original_ledger_id");
    }

    // 저장을 즉시 DB에 반영하면 지정한 FK 위반으로 실패하는지 확인한다. 실패한 문장은 SAVEPOINT로 되돌려 다음 확인을 이어 간다.
    private void assertForeignKeyViolation(Runnable save, String constraint) {
        entityManager.unwrap(org.hibernate.Session.class).doWork(connection ->
                connection.createStatement().execute("SAVEPOINT fk_check"));
        assertThatThrownBy(() -> {
            save.run();
            entityManager.flush();
        }).hasStackTraceContaining(constraint);
        entityManager.clear();
        entityManager.unwrap(org.hibernate.Session.class).doWork(connection ->
                connection.createStatement().execute("ROLLBACK TO SAVEPOINT fk_check"));
    }
}
