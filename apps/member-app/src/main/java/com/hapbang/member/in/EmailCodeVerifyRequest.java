package com.hapbang.member.in;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 가입 인증 코드 확인 요청 (#557) */
public record EmailCodeVerifyRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.")
        String email,
        @NotBlank(message = "인증 코드를 입력해 주세요.")
        @Pattern(regexp = "\\d{6}", message = "인증 코드는 숫자 6자리예요.")
        String code
) {
}
