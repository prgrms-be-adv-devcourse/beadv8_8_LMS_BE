package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// 지갑의 원장 반영(버킷별 증감·음수 방지·오버플로 방지)을 DB 없이 검증한다.
class WalletTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    @DisplayName("새 지갑의 네 가지 잔액은 모두 0이다")
    void create() {
        Wallet wallet = Wallet.create(1L);

        for (PointType pointType : PointType.values()) {
            for (BalanceBucket balanceBucket : BalanceBucket.values()) {
                assertThat(wallet.balanceOf(pointType, balanceBucket)).isZero();
            }
        }
    }

    @Test
    @DisplayName("원장 항목의 HP 종류·잔액 버킷에 해당하는 잔액만 바뀐다")
    void applyLedger_separatesBuckets() {
        Wallet wallet = saved(1L);

        wallet.applyLedger(ledger(wallet, PointType.CHARGE, BalanceBucket.AVAILABLE, 1_000L));
        wallet.applyLedger(ledger(wallet, PointType.REVENUE, BalanceBucket.AVAILABLE, 300L));
        wallet.applyLedger(ledger(wallet, PointType.CHARGE, BalanceBucket.HELD, 200L));
        wallet.applyLedger(ledger(wallet, PointType.REVENUE, BalanceBucket.HELD, 50L));

        assertThat(wallet.getChargeBalanceHp()).isEqualTo(1_000L);
        assertThat(wallet.getRevenueBalanceHp()).isEqualTo(300L);
        assertThat(wallet.getHeldChargeHp()).isEqualTo(200L);
        assertThat(wallet.getHeldRevenueHp()).isEqualTo(50L);
    }

    @Test
    @DisplayName("잔액이 음수가 되는 감소는 INSUFFICIENT_BALANCE로 거절하고 잔액을 바꾸지 않는다")
    void applyLedger_insufficientBalance() {
        Wallet wallet = saved(1L);
        wallet.applyLedger(ledger(wallet, PointType.CHARGE, BalanceBucket.AVAILABLE, 100L));

        assertThatThrownBy(() -> wallet.applyLedger(ledger(wallet, PointType.CHARGE, BalanceBucket.AVAILABLE, -101L)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));
        assertThat(wallet.getChargeBalanceHp()).isEqualTo(100L);
    }

    @Test
    @DisplayName("다른 버킷에 잔액이 있어도 해당 버킷이 부족하면 거절한다")
    void applyLedger_otherBucketDoesNotCover() {
        Wallet wallet = saved(1L);
        wallet.applyLedger(ledger(wallet, PointType.CHARGE, BalanceBucket.AVAILABLE, 1_000L));

        assertThatThrownBy(() -> wallet.applyLedger(ledger(wallet, PointType.REVENUE, BalanceBucket.AVAILABLE, -1L)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));
    }

    @Test
    @DisplayName("Long 범위를 넘는 증가는 BALANCE_OVERFLOW로 거절하고 잔액을 바꾸지 않는다")
    void applyLedger_overflow() {
        Wallet wallet = saved(1L);
        wallet.applyLedger(ledger(wallet, PointType.REVENUE, BalanceBucket.HELD, Long.MAX_VALUE));

        assertThatThrownBy(() -> wallet.applyLedger(ledger(wallet, PointType.REVENUE, BalanceBucket.HELD, 1L)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.BALANCE_OVERFLOW));
        assertThat(wallet.getHeldRevenueHp()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("한 번 반영한 원장 항목을 다시 반영하면 거절하고 잔액은 한 번만 바뀐다")
    void applyLedger_twice() {
        Wallet wallet = saved(1L);
        PointLedger ledger = ledger(wallet, PointType.CHARGE, BalanceBucket.AVAILABLE, 100L);
        wallet.applyLedger(ledger);

        assertThatThrownBy(() -> wallet.applyLedger(ledger)).isInstanceOf(IllegalStateException.class);
        assertThat(wallet.getChargeBalanceHp()).isEqualTo(100L);
    }

    @Test
    @DisplayName("저장소에서 읽은 원장 항목(기본 생성자로 만든 항목)은 반영하지 않는다")
    void applyLedger_loadedLedger() throws Exception {
        Wallet wallet = saved(1L);
        Constructor<PointLedger> constructor = PointLedger.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        PointLedger loaded = constructor.newInstance();

        assertThatThrownBy(() -> wallet.applyLedger(loaded)).isInstanceOf(IllegalStateException.class);
        assertThat(wallet.getChargeBalanceHp()).isZero();
    }

    @Test
    @DisplayName("다른 지갑의 원장 항목은 반영하지 않는다")
    void applyLedger_otherWallet() {
        Wallet wallet = saved(1L);
        Wallet other = saved(2L);

        assertThatThrownBy(() -> wallet.applyLedger(ledger(other, PointType.CHARGE, BalanceBucket.AVAILABLE, 100L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wallet.getChargeBalanceHp()).isZero();
    }

    @Test
    @DisplayName("저장 전이라 ID가 없는 지갑에는 원장 항목을 반영하지 않는다")
    void applyLedger_unsavedWallet() {
        Wallet wallet = Wallet.create(1L);

        assertThatThrownBy(() -> wallet.applyLedger(ledger(wallet, PointType.CHARGE, BalanceBucket.AVAILABLE, 100L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wallet.getChargeBalanceHp()).isZero();
    }

    // 저장된 지갑처럼 ID(회원 ID + 1000)가 있는 지갑을 만든다. 원장은 지갑을 ID로 가리킨다.
    private static Wallet saved(long userId) {
        Wallet wallet = Wallet.create(userId);
        ReflectionTestUtils.setField(wallet, "id", userId + 1_000L);
        return wallet;
    }

    // 지갑·HP 종류·버킷·증감으로 원장 항목을 만든다.
    private PointLedger ledger(Wallet wallet, PointType pointType, BalanceBucket balanceBucket, long amountHp) {
        return PointLedger.builder().walletId(wallet.getId()).pointType(pointType).balanceBucket(balanceBucket)
                .amountHp(amountHp).transactionType(LedgerTransactionType.CHARGE)
                .referenceType(BillingReferenceType.POINT_CHARGE).referenceId(1L)
                .operationKey("op").idempotencyKey("op:1").occurredAt(NOW).build();
    }
}
