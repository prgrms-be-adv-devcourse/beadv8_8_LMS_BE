package com.hapbang.member.in;

import jakarta.validation.constraints.NotBlank;

/** 비밀번호 변경 요청 (#499). 새 비밀번호 규칙은 도메인(Password)이 검사한다. 비밀번호는 toString에서 가린다 */
public record PasswordChangeRequest(
        @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
        String currentPassword,
        @NotBlank(message = "새 비밀번호를 입력해 주세요.")
        String newPassword
) {

    @Override
    public String toString() {
        return "PasswordChangeRequest[****]";
    }
}
