package com.hapbang.member.in;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 가입 인증 코드 받기 요청 (#557). 이메일 규칙은 가입 요청과 같다 */
public record EmailCodeRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.")
        String email
) {
}
