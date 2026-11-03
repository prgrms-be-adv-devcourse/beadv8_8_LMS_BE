package com.hapbang.member.domain;

import java.util.List;

import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;

/** 탈퇴가 막혔다 (#593). 409 {@code WITHDRAWAL_BLOCKED}에 모든 앱의 막힌 이유를 {@code reasons}로 한 번에 싣는다 */
public class WithdrawalBlockedException extends MemberException {

    private final List<WithdrawalBlockReasonDto> reasons;

    public WithdrawalBlockedException(List<WithdrawalBlockReasonDto> reasons) {
        super(MemberErrorCode.WITHDRAWAL_BLOCKED);
        this.reasons = List.copyOf(reasons);
        addProperty("reasons", this.reasons);
    }

    public List<WithdrawalBlockReasonDto> getReasons() {
        return reasons;
    }
}
