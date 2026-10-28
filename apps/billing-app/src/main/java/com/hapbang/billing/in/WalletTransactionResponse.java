package com.hapbang.billing.in;

import java.time.Instant;

import com.hapbang.billing.app.WalletTransactionResult;
import com.hapbang.billing.app.WalletTransactionType;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 내 HP 거래 하나의 응답 (#295). 증감은 부호를 그대로 두며, 화면 기본 금액은 availableChangeHp(가용 변화)다.
// totalChangeHp = availableChangeHp + heldChangeHp이고, charge·revenue도 HP 종류마다 같은 관계다.
public record WalletTransactionResponse(
        Long transactionId,
        WalletTransactionType type,
        String displayName,
        BillingReferenceType referenceType,
        Long referenceId,
        Instant occurredAt,
        long availableChangeHp,
        long heldChangeHp,
        long totalChangeHp,
        PointChangeResponse charge,
        PointChangeResponse revenue
) {

    public static WalletTransactionResponse from(WalletTransactionResult result) {
        return new WalletTransactionResponse(result.transactionId(), result.type(), result.type().getDisplayName(),
                result.referenceType(), result.referenceId(), result.occurredAt(), result.availableChangeHp(),
                result.heldChangeHp(), result.totalChangeHp(), PointChangeResponse.from(result.charge()),
                PointChangeResponse.from(result.revenue()));
    }

    // HP 종류(충전 HP 또는 수익 HP) 하나의 가용·보류·총액 변화
    public record PointChangeResponse(long availableChangeHp, long heldChangeHp, long totalChangeHp) {

        static PointChangeResponse from(WalletTransactionResult.PointChange change) {
            return new PointChangeResponse(change.availableChangeHp(), change.heldChangeHp(), change.totalChangeHp());
        }
    }
}
