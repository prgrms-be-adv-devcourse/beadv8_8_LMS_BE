package com.hapbang.shared.billing.dto;

// 탈퇴용 지갑 다시 열기 요청이다 (#623, docs/api/internal-withdrawals.md 4.2). 보상(다른 모듈에서 막힘, 회원 저장 실패)과
// 복구(30일 안 재로그인)에서 보낸다. withdrawalId는 닫기와 같은 탈퇴 작업 ID이며 멱등 키다.
// withdrawalId는 경로로, userId는 본문으로 보낸다. 이 record는 값이 양수인지만 검사한다.
public record WithdrawalReopenCommand(long withdrawalId, long userId) {

    public WithdrawalReopenCommand {
        BillingRequests.requirePositiveId(withdrawalId, "탈퇴 작업 ID");
        BillingRequests.requirePositiveId(userId, "회원 ID");
    }
}
