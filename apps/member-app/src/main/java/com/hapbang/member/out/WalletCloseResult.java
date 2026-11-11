package com.hapbang.member.out;

import java.util.List;
import java.util.Objects;

import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;

/**
 * 지갑 "확인하고 닫기" 결과 (#593, docs/api/internal-withdrawals.md 4.1)
 *
 * @param closed  지갑을 닫았으면 true (이유 없음), 막혀서 닫지 않았으면 false
 * @param reasons 지갑 쪽 막힌 이유 (잔액, 처리 중 환불·환전 등). 닫았으면 빈 목록
 */
public record WalletCloseResult(boolean closed, List<WithdrawalBlockReasonDto> reasons) {

    public WalletCloseResult {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons는 필수입니다."));
    }

    public static WalletCloseResult closedWallet() {
        return new WalletCloseResult(true, List.of());
    }

    public static WalletCloseResult blocked(List<WithdrawalBlockReasonDto> reasons) {
        return new WalletCloseResult(false, reasons);
    }
}
