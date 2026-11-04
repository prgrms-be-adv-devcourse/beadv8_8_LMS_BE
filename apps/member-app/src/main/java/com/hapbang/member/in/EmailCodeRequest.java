package com.hapbang.member.in;

import com.hapbang.member.domain.EmailVerificationPurpose;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 인증 코드 받기 요청 (#557, 가입·비밀번호 재설정). 이메일 규칙은 가입 요청과 같다 */
public record EmailCodeRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.")
        String email,
        /** 용도: SIGN_UP(가입) 또는 PASSWORD_RESET(비밀번호 재설정) */
        @NotNull(message = "용도를 정해 주세요.")
        EmailVerificationPurpose purpose
) {
}
