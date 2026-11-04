package com.hapbang.member.in;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 계정 생성 요청 (#517). 가입 요청과 같은 이메일·비밀번호·닉네임 규칙이고, 관리자가 만드는 계정이라 이메일 인증(#557)은 받지 않는다.
 * 비밀번호가 로그에 찍히지 않게 toString에서 가린다
 */
public record AdminAccountCreateRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.")
        String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password,
        @NotBlank(message = "닉네임을 입력해 주세요.")
        String nickname
) {

    @Override
    public String toString() {
        return "AdminAccountCreateRequest[email=" + email + ", password=****, nickname=" + nickname + "]";
    }
}
