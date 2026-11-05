package com.hapbang.member.in;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 요청 (#557). 새 비밀번호 규칙은 도메인(Password)이 검사한다. 비밀번호·토큰이 로그에 찍히지 않게 toString에서 가린다
 */
public record PasswordResetRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.")
        String email,
        /** 인증 코드 확인(purpose=PASSWORD_RESET)으로 받은 토큰 */
        @NotBlank(message = "이메일 인증을 먼저 해 주세요.")
        String emailVerificationToken,
        @NotBlank(message = "새 비밀번호를 입력해 주세요.")
        String newPassword
) {

    @Override
    public String toString() {
        return "PasswordResetRequest[email=" + email + ", emailVerificationToken=****, newPassword=****]";
    }
}
