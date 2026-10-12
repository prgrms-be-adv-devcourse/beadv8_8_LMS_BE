package com.hapbang.billing.app;

import java.util.List;
import java.util.Objects;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointType;

// 원장 기록 요청의 한 줄로, 지갑의 HP 종류·잔액 버킷에 반영할 부호 있는 증감(양수 증가, 음수 감소)을 담는다.
public record LedgerEntry(
        Long walletId,
        PointType pointType,
        BalanceBucket balanceBucket,
        long amountHp,
        Long originalLedgerId
) {

    // 필수 값을 확인하고 0 HP 증감을 INVALID_POINT_AMOUNT로 거절한다.
    public LedgerEntry {
        Objects.requireNonNull(walletId, "지갑 ID는 필수입니다.");
        Objects.requireNonNull(pointType, "HP 종류는 필수입니다.");
        Objects.requireNonNull(balanceBucket, "잔액 버킷은 필수입니다.");
        if (amountHp == 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
    }

    // 역분개가 아닌 일반 원장 줄을 만든다.
    public static LedgerEntry of(Long walletId, PointType pointType, BalanceBucket balanceBucket, long amountHp) {
        return new LedgerEntry(walletId, pointType, balanceBucket, amountHp, null);
    }

    // 같은 HP 종류 안에서 잔액 버킷 간 이동(출발 버킷 -금액, 도착 버킷 +금액)을 두 원장 줄로 만든다. HP 총량은 변하지 않는다.
    public static List<LedgerEntry> move(Long walletId, PointType pointType, BalanceBucket from, BalanceBucket to,
            long amountHp) {
        if (from == to) {
            throw new IllegalArgumentException("같은 잔액 버킷으로는 이동할 수 없습니다.");
        }
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        return List.of(of(walletId, pointType, from, -amountHp), of(walletId, pointType, to, amountHp));
    }
}
