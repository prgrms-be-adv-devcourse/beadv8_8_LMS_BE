package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 지갑의 원장 반영(버킷별 증감·음수 방지·오버플로 방지)을 DB 없이 검증한다.
class WalletTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    @DisplayName("파기로 닫으면 탈퇴 작업 ID 없이 닫히고, 다시 열기 대상(isClosedBy)이 아니다 (#674)")
    void closeForMasking() {
        Wallet wallet = Wallet.create(1L);

        assertThat(wallet.closeForMasking(NOW)).isTrue();

        assertThat(wallet.isClosed()).isTrue();
        assertThat(wallet.getClosedAt()).isEqualTo(NOW);
        assertThat(wallet.getClosedByWithdrawalId()).isNull();
        assertThat(wallet.isClosedBy(100L)).isFalse();
    }

    @Test
    @DisplayName("이미 탈퇴로 닫힌 지갑은 파기로 닫아도 탈퇴 작업 ID와 닫은 시각을 바꾸지 않는다 (#674)")
    void closeForMaskingKeepsWithdrawalClose() {
        Wallet wallet = Wallet.create(1L);
        Instant closedAt = NOW.minusSeconds(60);
        wallet.closeFor(100L, closedAt);

        assertThat(wallet.closeForMasking(NOW)).isFalse();

        assertThat(wallet.isClosedBy(100L)).isTrue();
        assertThat(wallet.getClosedAt()).isEqualTo(closedAt);
    }

    @Test
    @DisplayName("플랫폼 지갑은 파기로 닫을 수 없다 (#674)")
    void platformCannotCloseForMasking() {
        assertThatThrownBy(() -> Wallet.createPlatform().closeForMasking(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

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
    @Test
    @DisplayName("플랫폼 지갑은 회원이 없고 수수료 수입(FEE 가용 칸)만 바뀐다 (#336)")
    void platform_feeIncome() {
        Wallet platform = savedPlatform();

        platform.applyLedger(ledger(platform, PointType.FEE, BalanceBucket.AVAILABLE, 1_500L));
        platform.applyLedger(ledger(platform, PointType.FEE, BalanceBucket.AVAILABLE, 2_300L));

        assertThat(platform.isPlatform()).isTrue();
        assertThat(platform.getUserId()).isNull();
        assertThat(platform.getFeeBalanceHp()).isEqualTo(3_800L);
        assertThat(platform.balanceOf(PointType.FEE, BalanceBucket.AVAILABLE)).isEqualTo(3_800L);
        assertThat(platform.balanceOf(PointType.FEE, BalanceBucket.HELD)).isZero();
        assertThat(platform.getRevenueBalanceHp()).isZero();
    }

    @Test
    @DisplayName("플랫폼 지갑에 충전·수익 HP 원장, 회원 지갑에 수수료 수입 원장, 수수료 수입 보류 칸 원장은 반영하지 않는다 (#336)")
    void walletTypeGuards() {
        Wallet platform = savedPlatform();
        Wallet member = saved(1L);

        for (PointType pointType : new PointType[] {PointType.CHARGE, PointType.REVENUE}) {
            for (BalanceBucket balanceBucket : BalanceBucket.values()) {
                assertThatThrownBy(() -> platform.applyLedger(ledger(platform, pointType, balanceBucket, 100L)))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
        assertThatThrownBy(() -> member.applyLedger(ledger(member, PointType.FEE, BalanceBucket.AVAILABLE, 100L)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> platform.applyLedger(ledger(platform, PointType.FEE, BalanceBucket.HELD, 100L)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(platform.getFeeBalanceHp()).isZero();
        assertThat(member.getFeeBalanceHp()).isZero();
        assertThat(member.isPlatform()).isFalse();
    }

    @Test
    @DisplayName("프로모션 지갑은 회원이 없고 프로모션 HP의 가용·예약·보류 칸만 각각 바뀐다")
    void promotion_buckets() {
        Wallet promotion = savedPromotion();

        promotion.applyLedger(ledger(promotion, PointType.PROMOTION, BalanceBucket.AVAILABLE, 1_000L));
        promotion.applyLedger(ledger(promotion, PointType.PROMOTION, BalanceBucket.AVAILABLE, -300L));
        promotion.applyLedger(ledger(promotion, PointType.PROMOTION, BalanceBucket.RESERVED, 300L));
        promotion.applyLedger(ledger(promotion, PointType.PROMOTION, BalanceBucket.HELD, 50L));

        assertThat(promotion.isPromotion()).isTrue();
        assertThat(promotion.isPlatform()).isFalse();
        assertThat(promotion.getUserId()).isNull();
        assertThat(promotion.getPromotionBalanceHp()).isEqualTo(700L);
        assertThat(promotion.getReservedPromotionHp()).isEqualTo(300L);
        assertThat(promotion.getHeldPromotionHp()).isEqualTo(50L);
        assertThat(promotion.balanceOf(PointType.PROMOTION, BalanceBucket.AVAILABLE)).isEqualTo(700L);
        assertThat(promotion.balanceOf(PointType.PROMOTION, BalanceBucket.RESERVED)).isEqualTo(300L);
        assertThat(promotion.balanceOf(PointType.PROMOTION, BalanceBucket.HELD)).isEqualTo(50L);
        assertThat(promotion.getChargeBalanceHp() + promotion.getRevenueBalanceHp() + promotion.getFeeBalanceHp()).isZero();
    }

    @Test
    @DisplayName("프로모션 HP 칸도 음수가 되는 감소는 INSUFFICIENT_BALANCE로 거절하고, 다른 칸에 잔액이 있어도 메워 주지 않는다")
    void promotion_insufficientBalance() {
        Wallet promotion = savedPromotion();
        promotion.applyLedger(ledger(promotion, PointType.PROMOTION, BalanceBucket.AVAILABLE, 100L));
        promotion.applyLedger(ledger(promotion, PointType.PROMOTION, BalanceBucket.RESERVED, 500L));

        assertThatThrownBy(() -> promotion.applyLedger(
                ledger(promotion, PointType.PROMOTION, BalanceBucket.AVAILABLE, -101L)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));
        assertThatThrownBy(() -> promotion.applyLedger(
                ledger(promotion, PointType.PROMOTION, BalanceBucket.HELD, -1L)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));
        assertThat(promotion.getPromotionBalanceHp()).isEqualTo(100L);
        assertThat(promotion.getReservedPromotionHp()).isEqualTo(500L);
        assertThat(promotion.getHeldPromotionHp()).isZero();
    }

    @Test
    @DisplayName("회원·플랫폼 지갑에 프로모션 HP 원장, 프로모션 지갑에 충전·수익·수수료 원장, 프로모션 HP가 아닌 예약 칸 원장은 반영하지 않는다")
    void promotion_walletTypeGuards() {
        Wallet promotion = savedPromotion();
        Wallet platform = savedPlatform();
        Wallet member = saved(1L);

        for (BalanceBucket balanceBucket : BalanceBucket.values()) {
            assertThatThrownBy(() -> member.applyLedger(ledger(member, PointType.PROMOTION, balanceBucket, 100L)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> platform.applyLedger(ledger(platform, PointType.PROMOTION, balanceBucket, 100L)))
                    .isInstanceOf(IllegalStateException.class);
            for (PointType pointType : new PointType[] {PointType.CHARGE, PointType.REVENUE, PointType.FEE}) {
                assertThatThrownBy(() -> promotion.applyLedger(ledger(promotion, pointType, balanceBucket, 100L)))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
        for (PointType pointType : new PointType[] {PointType.CHARGE, PointType.REVENUE}) {
            assertThatThrownBy(() -> member.applyLedger(ledger(member, pointType, BalanceBucket.RESERVED, 100L)))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> platform.applyLedger(ledger(platform, PointType.FEE, BalanceBucket.RESERVED, 100L)))
                .isInstanceOf(IllegalStateException.class);
        for (BalanceBucket balanceBucket : BalanceBucket.values()) {
            assertThat(promotion.balanceOf(PointType.PROMOTION, balanceBucket)).isZero();
            assertThat(member.balanceOf(PointType.PROMOTION, balanceBucket)).isZero();
            assertThat(member.balanceOf(PointType.CHARGE, balanceBucket)).isZero();
            assertThat(platform.balanceOf(PointType.FEE, balanceBucket)).isZero();
        }
    }

    @Test
    @DisplayName("프로모션 지갑은 탈퇴·파기로 닫을 수 없다")
    void promotionCannotClose() {
        assertThatThrownBy(() -> Wallet.createPromotion().closeForMasking(NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Wallet.createPromotion().closeFor(1L, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("지갑 유형마다 담는 HP 종류와, HP 종류마다 있는 잔액 칸이 정해져 있다")
    void walletTypeAndPointTypeCells() {
        assertThat(WalletType.MEMBER.holds(PointType.CHARGE)).isTrue();
        assertThat(WalletType.MEMBER.holds(PointType.REVENUE)).isTrue();
        assertThat(WalletType.MEMBER.holds(PointType.FEE)).isFalse();
        assertThat(WalletType.MEMBER.holds(PointType.PROMOTION)).isFalse();
        assertThat(WalletType.PLATFORM.holds(PointType.FEE)).isTrue();
        assertThat(WalletType.PLATFORM.holds(PointType.PROMOTION)).isFalse();
        assertThat(WalletType.PROMOTION.holds(PointType.PROMOTION)).isTrue();
        assertThat(WalletType.PROMOTION.holds(PointType.FEE)).isFalse();

        assertThat(PointType.CHARGE.has(BalanceBucket.RESERVED)).isFalse();
        assertThat(PointType.REVENUE.has(BalanceBucket.RESERVED)).isFalse();
        assertThat(PointType.FEE.has(BalanceBucket.HELD)).isFalse();
        assertThat(PointType.FEE.has(BalanceBucket.RESERVED)).isFalse();
        for (BalanceBucket balanceBucket : BalanceBucket.values()) {
            assertThat(PointType.PROMOTION.has(balanceBucket)).isTrue();
        }
    }

    private static Wallet savedPromotion() {
        Wallet wallet = Wallet.createPromotion();
        ReflectionTestUtils.setField(wallet, "id", 2L);
        return wallet;
    }

    private static Wallet savedPlatform() {
        Wallet wallet = Wallet.createPlatform();
        ReflectionTestUtils.setField(wallet, "id", 1L);
        return wallet;
    }

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
