package com.hapbang.member.in;

import jakarta.validation.constraints.NotBlank;

/** 탈퇴 회원 복구 요청 (#593). 티켓이 로그에 찍히지 않게 toString에서 가린다 */
public record RestoreRequest(
        @NotBlank(message = "복구 티켓이 없습니다. 다시 로그인해 주세요.")
        String restoreTicket
) {

    @Override
    public String toString() {
        return "RestoreRequest[restoreTicket=****]";
    }
}
