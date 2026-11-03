package com.hapbang.shared.billing.dto;

// 탈퇴용 지갑 닫기 요청이다 (#623, docs/api/internal-withdrawals.md 4.1). withdrawalId는 회원 앱의 탈퇴 작업 ID이며 멱등 키다.
// withdrawalId는 경로로, userId는 본문으로 보낸다. 이 record는 값이 양수인지만 검사한다.
public record WithdrawalCloseCommand(long withdrawalId, long userId) {

    public WithdrawalCloseCommand {
        BillingRequests.requirePositiveId(withdrawalId, "탈퇴 작업 ID");
        BillingRequests.requirePositiveId(userId, "회원 ID");
    }
}
