package com.hapbang.billing.app;

import com.hapbang.shared.billing.domain.WithdrawalReopenOutcome;

// 탈퇴용 지갑 다시 열기 결과 (#623). replayed가 true면 같은 탈퇴 작업의 처음 결과를 다시 돌려준 것이다.
public record WalletWithdrawalReopenResult(
        long withdrawalId,
        WithdrawalReopenOutcome outcome,
        boolean replayed
) {
}
