package com.hapbang.shared.billing.dto;

import com.hapbang.shared.billing.domain.WithdrawalReopenOutcome;

// 탈퇴용 지갑 다시 열기 결과 (#623). replayed가 true면 같은 탈퇴 작업 ID로 다시 보내 처음 결과를 돌려받은 것이다.
public record WithdrawalReopenResultDto(
        long withdrawalId,
        WithdrawalReopenOutcome outcome,
        boolean replayed
) {
}
