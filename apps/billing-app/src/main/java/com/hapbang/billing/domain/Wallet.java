package com.hapbang.billing.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;

// 회원별 충전·수익 HP의 가용·보류 잔액과 플랫폼의 수수료 수입을 원장 합계의 캐시로 보관한다.
// 회원 지갑(MEMBER)은 회원 한 명에 하나이고 수수료 수입이 없다. 플랫폼 지갑(PLATFORM, #336)은 회원이 없고(user_id 없음) 하나뿐이며
// 수수료 수입만 있다. 프로모션 지갑(PROMOTION)도 회원이 없고 하나뿐이며 플랫폼 쿠폰 재원인 프로모션 HP의 가용·예약·보류 칸만 있다.
// 플랫폼·프로모션 지갑은 Flyway 마이그레이션이 만들고, 회원 기능은 user_id로 지갑을 찾으므로 닿지 않는다.
@Entity
@Table(name = "wallet", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_wallet_user_id", columnNames = {"user_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(constraints = "(charge_balance_hp >= 0) AND (revenue_balance_hp >= 0) AND (held_charge_hp >= 0) AND (held_revenue_hp >= 0)"
        + " AND (fee_balance_hp >= 0) AND (promotion_balance_hp >= 0) AND (reserved_promotion_hp >= 0)"
        + " AND (held_promotion_hp >= 0)")
public class Wallet extends BillingUpdatedEntity {

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'MEMBER'")
    @Column(name = "wallet_type", nullable = false, updatable = false)
    private WalletType walletType = WalletType.MEMBER;

    // 회원 지갑의 소유자 회원 ID. 플랫폼 지갑은 없다
    @Column(name = "user_id", updatable = false)
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

    // 수수료 수입(FEE, 가용 칸만). 플랫폼 지갑만 쓴다 (#336)
    @ColumnDefault("0")
    @Column(name = "fee_balance_hp", nullable = false)
    private Long feeBalanceHp = 0L;

    // 프로모션 HP 가용 칸(아직 쿠폰 예산으로 묶지 않은 HP). 프로모션 지갑만 쓴다
    @ColumnDefault("0")
    @Column(name = "promotion_balance_hp", nullable = false)
    private Long promotionBalanceHp = 0L;

    // 프로모션 HP 예약 칸(쿠폰 예산으로 묶은 HP). 쿠폰 예산 예약들의 남은 예산 합과 같다. 프로모션 지갑만 쓴다
    @ColumnDefault("0")
    @Column(name = "reserved_promotion_hp", nullable = false)
    private Long reservedPromotionHp = 0L;

    // 프로모션 HP 보류 칸(주문 항목에 보류한 쿠폰 예산). 프로모션 지갑만 쓴다
    @ColumnDefault("0")
    @Column(name = "held_promotion_hp", nullable = false)
    private Long heldPromotionHp = 0L;

    // 지갑을 닫은 탈퇴 작업 ID (#623). 파기로 닫았으면 비어 있다 (#674, BD-145)
    @Column(name = "closed_by_withdrawal_id")
    private Long closedByWithdrawalId;

    // 지갑을 닫은 시각이자 닫힘 표시 (#623, #674). 닫힌 지갑은 새로 HP가 드나드는 신청(충전·보류 생성·환불·환전)을 거절하고,
    // 이미 생긴 의무(보류 반환·지급 받기·복구 배치)는 받는다. 닫힘 확인은 지갑 락 아래에서 한다
    @Column(name = "closed_at", columnDefinition = "timestamptz")
    private Instant closedAt;

    // 지갑 유형과 소유자 회원 ID를 지정하고 모든 잔액이 0인 지갑 객체를 초기화한다.
    private Wallet(WalletType walletType, Long userId) {
        this.walletType = walletType;
        this.userId = userId;
    }

    // 회원 ID로 초기 잔액이 0인 새 회원 지갑 객체를 생성한다.
    public static Wallet create(Long userId) {
        return new Wallet(WalletType.MEMBER, userId);
    }

    // 회원이 없는 플랫폼 지갑 객체를 생성한다. 운영에서는 Flyway 마이그레이션이 한 행을 만들고, 이 메서드는 도메인 테스트가 쓴다.
    public static Wallet createPlatform() {
        return new Wallet(WalletType.PLATFORM, null);
    }

    // 회원이 없고 프로모션 HP만 담는 프로모션 지갑 객체를 생성한다. 운영에서는 Flyway 마이그레이션이 한 행을 만들고, 이 메서드는 도메인 테스트가 쓴다.
    public static Wallet createPromotion() {
        return new Wallet(WalletType.PROMOTION, null);
    }

    public boolean isPlatform() {
        return walletType == WalletType.PLATFORM;
    }

    public boolean isPromotion() {
        return walletType == WalletType.PROMOTION;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    // 회원 정보를 파기할 때 열린 지갑을 닫는다 (#674, BD-145). 근거가 될 탈퇴 작업 ID가 없어 비워 두므로 다시 열기로 열리지 않는다.
    // 이미 닫혀 있으면(탈퇴로 닫힘) 바꾸지 않는다. 닫았으면 true
    public boolean closeForMasking(Instant closedAt) {
        rejectUnlessMember();
        if (isClosed()) {
            return false;
        }
        this.closedAt = closedAt;
        return true;
    }

    // 탈퇴 작업으로 지갑을 닫는다 (#623). 이미 다른 탈퇴 작업으로 닫혀 있으면 마지막 탈퇴 작업으로 바꾼다(다시 열기는 마지막 작업만 연다).
    public void closeFor(long withdrawalId, Instant closedAt) {
        rejectUnlessMember();
        this.closedByWithdrawalId = withdrawalId;
        this.closedAt = closedAt;
    }

    // 닫기는 회원 지갑만 한다. 플랫폼·프로모션 지갑은 회원이 없어 탈퇴·파기로 닫지 않는다
    private void rejectUnlessMember() {
        if (walletType != WalletType.MEMBER) {
            throw new IllegalStateException("회원 지갑이 아닌 플랫폼·프로모션 지갑은 닫을 수 없습니다.");
        }
    }

    // 이 탈퇴 작업으로 닫혀 있는지 확인한다
    public boolean isClosedBy(long withdrawalId) {
        return closedByWithdrawalId != null && closedByWithdrawalId == withdrawalId;
    }

    // 닫힘 표시를 지운다 (#623, 탈퇴 보상·복구)
    public void reopen() {
        this.closedByWithdrawalId = null;
        this.closedAt = null;
    }

    // 새로 HP가 드나드는 신청 전에 지갑 락 아래에서 부른다. 닫힌 지갑이면 WALLET_CLOSED(409)로 거절한다 (#623)
    public void requireOpen() {
        if (isClosed()) {
            throw new BillingException(BillingErrorCode.WALLET_CLOSED);
        }
    }

    // HP 종류·잔액 버킷에 해당하는 캐시 잔액을 반환한다. 그 HP 종류에 없는 칸(수수료 수입의 보류, 충전·수익 HP의 예약 등)은 0이다.
    public long balanceOf(PointType pointType, BalanceBucket balanceBucket) {
        if (!pointType.has(balanceBucket)) {
            return 0L;
        }
        return switch (pointType) {
            case CHARGE -> balanceBucket == BalanceBucket.AVAILABLE ? chargeBalanceHp : heldChargeHp;
            case REVENUE -> balanceBucket == BalanceBucket.AVAILABLE ? revenueBalanceHp : heldRevenueHp;
            case FEE -> feeBalanceHp;
            case PROMOTION -> switch (balanceBucket) {
                case AVAILABLE -> promotionBalanceHp;
                case RESERVED -> reservedPromotionHp;
                case HELD -> heldPromotionHp;
            };
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
        // 충전·수익 HP는 회원 지갑에만, 수수료 수입은 플랫폼 지갑에만, 프로모션 HP는 프로모션 지갑에만 있다. 섞이면 불변식 위반이다
        if (!walletType.holds(ledger.getPointType())) {
            throw new IllegalStateException("지갑 유형에 맞지 않는 HP 종류의 원장 항목은 반영할 수 없습니다.");
        }
        // 수수료 수입은 가용 칸만, 예약 칸은 프로모션 HP만 있다
        if (!ledger.getPointType().has(ledger.getBalanceBucket())) {
            throw new IllegalStateException("HP 종류에 없는 잔액 칸의 원장 항목은 반영할 수 없습니다.");
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
            case FEE -> feeBalanceHp = changed;
            case PROMOTION -> {
                switch (ledger.getBalanceBucket()) {
                    case AVAILABLE -> promotionBalanceHp = changed;
                    case RESERVED -> reservedPromotionHp = changed;
                    case HELD -> heldPromotionHp = changed;
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
