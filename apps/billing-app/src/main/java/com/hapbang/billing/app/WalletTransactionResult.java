package com.hapbang.billing.app;

import java.time.Instant;

import com.hapbang.billing.out.LedgerOperationSum;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 내 지갑의 HP 거래 하나(업무 작업 하나) (#295). 증감은 부호를 그대로 두고, HP 종류마다 가용·보류·총액(가용 + 보류) 변화를 담는다.
// 보류·반환은 가용과 보류 사이 이동이라 총액 변화가 0이다.
public record WalletTransactionResult(
        Long transactionId,
        WalletTransactionType type,
        BillingReferenceType referenceType,
        Long referenceId,
        Instant occurredAt,
        PointChange charge,
        PointChange revenue
) {

    static WalletTransactionResult from(LedgerOperationSum sum) {
        return new WalletTransactionResult(sum.firstLedgerId(), WalletTransactionType.of(sum), sum.referenceType(),
                sum.referenceId(), sum.occurredAt(),
                new PointChange(sum.chargeAvailableHp(), sum.chargeHeldHp()),
                new PointChange(sum.revenueAvailableHp(), sum.revenueHeldHp()));
    }

    // 가용 HP 변화(충전 + 수익). 화면 기본 표시 금액이다
    public long availableChangeHp() {
        return Math.addExact(charge.availableChangeHp(), revenue.availableChangeHp());
    }

    // 보류 HP 변화(충전 + 수익)
    public long heldChangeHp() {
        return Math.addExact(charge.heldChangeHp(), revenue.heldChangeHp());
    }

    // 총 HP 변화 = 가용 변화 + 보류 변화
    public long totalChangeHp() {
        return Math.addExact(availableChangeHp(), heldChangeHp());
    }

    // HP 종류 하나의 가용·보류 변화
    public record PointChange(long availableChangeHp, long heldChangeHp) {

        public long totalChangeHp() {
            return Math.addExact(availableChangeHp, heldChangeHp);
        }
    }
}
