package com.hapbang.shared.billing.dto;

import java.util.List;

import com.hapbang.shared.billing.domain.WithdrawalCloseOutcome;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;

// 탈퇴용 지갑 닫기 결과 (#623). CLOSED면 reasons가 빈 목록이고, BLOCKED면 지갑이 적은 막힌 이유(잔액, 처리 중인 환불·환전·충전)다.
// replayed가 true면 같은 탈퇴 작업 ID로 다시 보내 처음 결과를 돌려받은 것이다(BLOCKED였으면 그때의 이유 그대로).
public record WithdrawalCloseResultDto(
        long withdrawalId,
        WithdrawalCloseOutcome outcome,
        List<WithdrawalBlockReasonDto> reasons,
        boolean replayed
) {

    public WithdrawalCloseResultDto {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
