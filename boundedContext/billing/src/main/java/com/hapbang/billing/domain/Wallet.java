package com.hapbang.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;

// 회원별 충전·수익 HP의 가용·보류 잔액을 원장 합계의 캐시로 보관한다.
@Entity
@Table(name = "wallet", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_wallet_user_id", columnNames = {"user_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(charge_balance_hp >= 0) AND (revenue_balance_hp >= 0) AND (held_charge_hp >= 0) AND (held_revenue_hp >= 0)")
public class Wallet extends BillingUpdatedEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ColumnDefault("0")
    @Column(name = "charge_balance_hp", nullable = false)
    private Long chargeBalanceHp = 0L;

    @ColumnDefault("0")
    @Column(name = "revenue_balance_hp", nullable = false)
    private Long revenueBalanceHp = 0L;

    @ColumnDefault("0")
    @Column(name = "held_charge_hp", nullable = false)
    private Long heldChargeHp = 0L;

    @ColumnDefault("0")
    @Column(name = "held_revenue_hp", nullable = false)
    private Long heldRevenueHp = 0L;

    // 소유자 회원 ID를 지정하고 네 가지 HP 잔액이 0인 지갑 객체를 초기화한다.
    private Wallet(Long userId) {
        this.userId = userId;
    }

    // 회원 ID로 초기 잔액이 0인 새 지갑 객체를 생성한다.
    public static Wallet create(Long userId) {
        return new Wallet(userId);
    }

    // HP 종류·잔액 버킷에 해당하는 캐시 잔액을 반환한다.
    public long balanceOf(PointType pointType, BalanceBucket balanceBucket) {
        return switch (pointType) {
            case CHARGE -> balanceBucket == BalanceBucket.AVAILABLE ? chargeBalanceHp : heldChargeHp;
            case REVENUE -> balanceBucket == BalanceBucket.AVAILABLE ? revenueBalanceHp : heldRevenueHp;
        };
    }

    // 이 지갑의 원장 항목 하나를 HP 종류·잔액 버킷에 반영하며, 잔액 변경은 원장 항목으로만 한다.
    public void applyLedger(PointLedger ledger) {
        // 원장 ID는 IDENTITY 전략에서 INSERT 시점에 정해지므로 ID 대신 새로 만든 항목인지로 재반영을 막는다.
        if (!ledger.isPendingApplication()) {
            throw new IllegalStateException("이미 반영했거나 저장소에서 읽은 원장 항목은 다시 반영할 수 없습니다.");
        }
        // 원장은 지갑을 ID로 참조한다. 저장 전(ID 없는) 지갑이나 다른 지갑 ID의 원장은 반영하지 않는다.
        if (getId() == null || !getId().equals(ledger.getWalletId())) {
            throw new IllegalArgumentException("다른 지갑의 원장 항목은 반영할 수 없습니다.");
        }
        long changed = changedBalance(balanceOf(ledger.getPointType(), ledger.getBalanceBucket()), ledger.getAmountHp());
        ledger.markApplied();
        switch (ledger.getPointType()) {
            case CHARGE -> {
                if (ledger.getBalanceBucket() == BalanceBucket.AVAILABLE) {
                    chargeBalanceHp = changed;
                } else {
                    heldChargeHp = changed;
                }
            }
            case REVENUE -> {
                if (ledger.getBalanceBucket() == BalanceBucket.AVAILABLE) {
                    revenueBalanceHp = changed;
                } else {
                    heldRevenueHp = changed;
                }
            }
        }
    }

    // 현재 잔액에 증감을 더하며, 범위를 넘으면 BALANCE_OVERFLOW, 음수가 되면 INSUFFICIENT_BALANCE로 거절한다.
    private static long changedBalance(long current, long amountHp) {
        long changed;
        try {
            changed = Math.addExact(current, amountHp);
        } catch (ArithmeticException exception) {
            throw new BillingException(BillingErrorCode.BALANCE_OVERFLOW, exception);
        }
        if (changed < 0) {
            throw new BillingException(BillingErrorCode.INSUFFICIENT_BALANCE);
        }
        return changed;
    }
}
