package com.hapbang.billing.e2e;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.WalletCreateUseCase;
import com.hapbang.billing.app.WalletReconciliation;
import com.hapbang.billing.app.WalletReconciliationUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PgProvider;
import com.hapbang.billing.domain.PointCharge;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointLedger;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointChargeRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import jakarta.persistence.EntityManager;

/** 실제 billing 엔진을 사용하는 격리 DB 준비·조회. production 응답이나 금융 엔진을 대체하지 않는다. */
@Service
@Profile("e2e")
public class BillingE2eFixtures {

    private final EntityManager entityManager;
    private final WalletRepository wallets;
    private final PointChargeRepository charges;
    private final WalletCreateUseCase walletCreate;
    private final LedgerRecordUseCase ledger;
    private final WalletReconciliationUseCase reconciliation;

    public BillingE2eFixtures(EntityManager entityManager, WalletRepository wallets, PointChargeRepository charges,
            WalletCreateUseCase walletCreate, LedgerRecordUseCase ledger, WalletReconciliationUseCase reconciliation) {
        this.entityManager = entityManager;
        this.wallets = wallets;
        this.charges = charges;
        this.walletCreate = walletCreate;
        this.ledger = ledger;
        this.reconciliation = reconciliation;
    }

    /** 테스트 사이에 이 프로세스의 billing 스키마만 비운다. Flyway 이력·스키마는 유지한다. */
    @Transactional
    public void reset() {
        List<String> tables = entityManager.createNativeQuery("""
                select tablename from pg_tables
                where schemaname = 'billing' and tablename <> 'flyway_schema_history'
                order by tablename
                """, String.class).getResultList();
        String targets = tables.stream().map(table -> "billing.\"" + table.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(", "));
        if (!targets.isEmpty()) {
            entityManager.createNativeQuery("truncate table " + targets + " restart identity cascade").executeUpdate();
        }
        entityManager.clear();
        wallets.save(Wallet.createPlatform());
        wallets.save(Wallet.createPromotion());
        entityManager.flush();
    }

    /** 양수 seed만 더한다. 0은 해당 HP 종류의 원장·충전 건을 만들지 않는다. */
    @Transactional
    public WalletView seedWallet(long userId, long chargeHp, long revenueHp) {
        if (userId <= 0 || chargeHp < 0 || revenueHp < 0) {
            throw new IllegalArgumentException("회원 ID는 양수, 추가 HP는 0 이상이어야 합니다.");
        }
        if (chargeHp > 0 && (chargeHp < 1_000 || chargeHp % 1_000 != 0)) {
            throw new BillingException(BillingErrorCode.CHARGE_AMOUNT_INVALID);
        }
        Wallet created = walletCreate.getOrCreate(userId);
        Wallet wallet = ledger.lockWallet(created.getId());
        if (chargeHp > 0 || revenueHp > 0) wallet.requireOpen();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (chargeHp > 0) {
            // 충전 확인 전제만 준비한다. 이후 보류·FIFO·복원은 실제 PointCharge와 원장으로 처리된다.
            PointCharge charge = charges.save(PointCharge.builder()
                    .walletId(wallet.getId()).amountHp(chargeHp).paymentAmountKrw(chargeHp)
                    .remainingPrincipalHp(chargeHp).pgProvider(PgProvider.TOSS_PAYMENTS)
                    .pgOrderId("E2E-" + UUID.randomUUID()).status(PointChargeStatus.COMPLETED)
                    .requestedAt(now).completedAt(now).build());
            entityManager.flush();
            ledger.record(new LedgerOperation("CHARGE:" + charge.getId(), LedgerTransactionType.CHARGE,
                    BillingReferenceType.POINT_CHARGE, charge.getId(), "HTTP E2E 충전 전제", now,
                    List.of(LedgerEntry.of(wallet.getId(), PointType.CHARGE, BalanceBucket.AVAILABLE, chargeHp))));
        }
        if (revenueHp > 0) {
            ledger.record(new LedgerOperation("E2E-SEED-REVENUE:" + UUID.randomUUID(), LedgerTransactionType.PAYOUT,
                    BillingReferenceType.SETTLEMENT, wallet.getId(), "HTTP E2E 수익 전제", now,
                    List.of(LedgerEntry.of(wallet.getId(), PointType.REVENUE, BalanceBucket.AVAILABLE, revenueHp))));
        }
        entityManager.flush();
        return WalletView.from(wallet);
    }

    @Transactional
    public WalletView close(long userId) {
        Wallet found = wallets.findByUserId(userId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
        Wallet wallet = ledger.lockWallet(found.getId());
        wallet.closeForMasking(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return WalletView.from(wallet);
    }

    /** 존재하지 않는 지갑은 만들지 않는다. 실제 원장·충전 원금·대사를 HTTP 경계 밖의 테스트에 제공한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(long userId) {
        Wallet wallet = wallets.findByUserId(userId).orElse(null);
        if (wallet == null) return new Snapshot(null, List.of(), List.of(), 0, null);
        List<LedgerView> rows = entityManager.createQuery("""
                select l from PointLedger l where l.walletId = :walletId order by l.id
                """, PointLedger.class).setParameter("walletId", wallet.getId()).getResultList().stream()
                .map(LedgerView::from).toList();
        List<ChargeView> chargeRows = entityManager.createQuery("""
                select c from PointCharge c where c.walletId = :walletId order by c.completedAt, c.id
                """, PointCharge.class).setParameter("walletId", wallet.getId()).getResultList().stream()
                .map(ChargeView::from).toList();
        long principal = chargeRows.stream().map(ChargeView::remainingPrincipalHp).reduce(0L, Math::addExact);
        return new Snapshot(WalletView.from(wallet), rows, chargeRows, principal, reconciliation.reconcile(wallet.getId()));
    }

    public record WalletView(long walletId, Long userId, long chargeBalanceHp, long revenueBalanceHp,
            long heldChargeHp, long heldRevenueHp, long availableTotalHp, long heldTotalHp, boolean closed) {
        static WalletView from(Wallet wallet) {
            return new WalletView(wallet.getId(), wallet.getUserId(), wallet.getChargeBalanceHp(), wallet.getRevenueBalanceHp(),
                    wallet.getHeldChargeHp(), wallet.getHeldRevenueHp(),
                    Math.addExact(wallet.getChargeBalanceHp(), wallet.getRevenueBalanceHp()),
                    Math.addExact(wallet.getHeldChargeHp(), wallet.getHeldRevenueHp()), wallet.isClosed());
        }
    }

    public record LedgerView(long id, long walletId, PointType pointType, BalanceBucket balanceBucket, long amountHp,
            LedgerTransactionType transactionType, BillingReferenceType referenceType, Long referenceId,
            String operationKey, String idempotencyKey, Instant occurredAt) {
        static LedgerView from(PointLedger row) {
            return new LedgerView(row.getId(), row.getWalletId(), row.getPointType(), row.getBalanceBucket(), row.getAmountHp(),
                    row.getTransactionType(), row.getReferenceType(), row.getReferenceId(), row.getOperationKey(),
                    row.getIdempotencyKey(), row.getOccurredAt());
        }
    }

    public record ChargeView(long chargeId, long amountHp, long remainingPrincipalHp, PointChargeStatus status,
            Instant completedAt, String pgOrderId) {
        static ChargeView from(PointCharge charge) {
            return new ChargeView(charge.getId(), charge.getAmountHp(), charge.getRemainingPrincipalHp(), charge.getStatus(),
                    charge.getCompletedAt(), charge.getPgOrderId());
        }
    }

    public record Snapshot(WalletView wallet, List<LedgerView> ledgers, List<ChargeView> charges,
            long chargePrincipalHp, WalletReconciliation reconciliation) { }
}
