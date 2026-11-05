package com.hapbang.member.in;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 이메일·비밀번호 가입 요청 (#499). 비밀번호·닉네임 규칙은 도메인(Password, Nickname)이 검사한다.
 * 비밀번호가 로그에 찍히지 않게 toString에서 가린다
 */
public record SignUpRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.")
        String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password,
        @NotBlank(message = "닉네임을 입력해 주세요.")
        String nickname,
        /**
         * 인증 코드 확인(POST /api/v1/members/email-codes/verify)으로 받은 토큰 (#557). 없으면 가입에서 400 EMAIL_NOT_VERIFIED
         * (부하 시험 계정 이메일만 없어도 된다, LoadTestAccount)
         */
        String emailVerificationToken
) {

    @Override
    public String toString() {
        return "SignUpRequest[email=" + email + ", password=****, nickname=" + nickname + ", emailVerificationToken=****]";
    }
}
