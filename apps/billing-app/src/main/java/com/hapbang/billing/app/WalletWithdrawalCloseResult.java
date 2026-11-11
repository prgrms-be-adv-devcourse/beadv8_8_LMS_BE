package com.hapbang.billing.app;

import java.util.List;

import com.hapbang.shared.billing.domain.WithdrawalCloseOutcome;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;

// 탈퇴용 지갑 닫기 결과 (#623). CLOSED면 reasons가 비어 있다. replayed가 true면 같은 탈퇴 작업의 처음 결과를 다시 돌려준 것이다.
public record WalletWithdrawalCloseResult(
        long withdrawalId,
        WithdrawalCloseOutcome outcome,
        List<WithdrawalBlockReasonDto> reasons,
        boolean replayed
) {
}
