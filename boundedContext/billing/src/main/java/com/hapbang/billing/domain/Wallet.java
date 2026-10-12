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
}
