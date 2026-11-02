package com.hapbang.member.in;

import org.springframework.http.ResponseEntity;

import com.hapbang.member.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 이메일·비밀번호 로그인 API Swagger 명세 (#499). 매핑은 {@link ApiV1MemberPasswordController} */
@Tag(name = "비밀번호 로그인", description = "이메일·비밀번호 가입, 로그인, 비밀번호 변경")
public interface ApiV1MemberPasswordSpec {

    @Operation(summary = "가입",
            description = "로그인 없이 부른다. 이메일·비밀번호·닉네임으로 가입하고 역할은 유저다. 가입만 하고 로그인은 따로 한다. "
                    + "이메일은 앞뒤 공백을 지우고 소문자로 저장한다. 이미 가입된 이메일(구글 가입 포함)이면 409 DUPLICATE_EMAIL. "
                    + "비밀번호는 8~64자, 영문과 숫자를 하나 이상씩, 영문·숫자·키보드 특수문자만(공백·한글 불가). "
                    + "어기면 400 INVALID_PASSWORD_LENGTH·INVALID_PASSWORD_CHARACTER·PASSWORD_LETTER_AND_DIGIT_REQUIRED. "
                    + "닉네임은 내 정보 수정과 같은 규칙. 메일 인증은 아직 없다.")
    RsData<MemberMeResponse> signUp(SignUpRequest request);

    @Operation(summary = "로그인",
            description = "로그인 없이 부른다. 맞으면 refresh token 쿠키(REFRESH_TOKEN)를 주고, access token은 재발급 API "
                    + "(POST /api/v1/members/auth/refresh)로 받는다 (구글 로그인과 같다). "
                    + "없는 이메일과 틀린 비밀번호는 모두 401 INVALID_CREDENTIALS. "
                    + "같은 이메일로 5번 연속 틀리면 15분 동안 429 LOGIN_LOCKED. 정지·탈퇴 회원은 403 LOGIN_NOT_ALLOWED.")
    ResponseEntity<RsData<MemberMeResponse>> login(PasswordLoginRequest request);

    @Operation(summary = "비밀번호 변경",
            description = "로그인한 회원 본인. 현재 비밀번호가 틀리면 400 INVALID_CURRENT_PASSWORD, "
                    + "비밀번호로 가입하지 않은 회원(구글 회원)은 409 PASSWORD_NOT_SET. 새 비밀번호 규칙은 가입과 같다. "
                    + "바꾸면 모든 기기의 refresh token을 폐기하고, 이 기기에는 새 refresh token 쿠키를 준다 "
                    + "(다른 기기는 access token 만료 뒤 다시 로그인).")
    ResponseEntity<RsData<Void>> changePassword(Long userId, PasswordChangeRequest request);
}
