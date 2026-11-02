package com.hapbang.member.in;

import jakarta.validation.constraints.NotBlank;

/** 이메일·비밀번호 로그인 요청 (#499). 비밀번호가 로그에 찍히지 않게 toString에서 가린다 */
public record PasswordLoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password
) {

    @Override
    public String toString() {
        return "PasswordLoginRequest[email=" + email + ", password=****]";
    }
}
