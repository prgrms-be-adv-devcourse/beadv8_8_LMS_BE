package com.hapbang.member.in;

import org.springframework.http.ResponseEntity;

import com.hapbang.member.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 이메일·비밀번호 로그인 API Swagger 명세 (#499). 매핑은 {@link ApiV1MemberPasswordController} */
@Tag(name = "비밀번호 로그인", description = "이메일·비밀번호 가입, 로그인, 비밀번호 변경")
public interface ApiV1MemberPasswordSpec {

    @Operation(summary = "가입 인증 코드 받기",
            description = "로그인 없이 부른다 (#557). 가입할 이메일로 6자리 인증 코드를 보낸다. 코드는 10분 유효하고, 다시 받으면 이전 코드는 못 쓴다. "
                    + "이미 가입된 이메일(구글 가입 포함)이면 보내지 않고 409 DUPLICATE_EMAIL. "
                    + "같은 이메일은 1분에 한 번(429 EMAIL_CODE_RESEND_TOO_SOON), 한 IP는 1시간에 10통(429 EMAIL_CODE_TOO_MANY_REQUESTS)까지 받을 수 있다. "
                    + "메일을 보내지 못하면 503 EMAIL_SEND_FAILED(바로 다시 시도 가능). "
                    + "응답 data: expiresInSeconds(코드 유효 시간), resendAfterSeconds(다시 받을 수 있을 때까지).")
    RsData<EmailCodeResponse> sendEmailCode(EmailCodeRequest request,
                                            @Parameter(hidden = true) HttpServletRequest httpRequest);

    @Operation(summary = "가입 인증 코드 확인",
            description = "로그인 없이 부른다 (#557). 메일로 받은 6자리 코드를 확인하고, 맞으면 인증 토큰(30분, 이 이메일로 한 번 가입 가능)을 준다. "
                    + "틀리면 400 INVALID_EMAIL_CODE(코드 하나에 5번까지). 5번째로 틀리거나, 받은 코드가 없거나 10분이 지났으면 "
                    + "400 EMAIL_CODE_EXPIRED(코드를 다시 받아야 함). 코드는 숫자 6자리(아니면 400). "
                    + "응답 data: emailVerificationToken(가입 요청에 넣는다), expiresInSeconds.")
    RsData<EmailVerificationTokenResponse> verifyEmailCode(EmailCodeVerifyRequest request);

    @Operation(summary = "가입",
            description = "로그인 없이 부른다. 이메일·비밀번호·닉네임으로 가입하고 역할은 유저다. 가입만 하고 로그인은 따로 한다. "
                    + "이메일은 앞뒤 공백을 지우고 소문자로 저장한다. 이미 가입된 이메일(구글 가입 포함)이면 409 DUPLICATE_EMAIL. "
                    + "다른 회원(탈퇴 회원 포함)이 쓰는 닉네임이면 대소문자만 달라도 409 DUPLICATE_NICKNAME (#646, 미리 확인: GET /api/v1/members/nicknames/availability). "
                    + "비밀번호는 8~64자, 영문과 숫자를 하나 이상씩, 영문·숫자·키보드 특수문자만(공백·한글 불가). "
                    + "어기면 400 INVALID_PASSWORD_LENGTH·INVALID_PASSWORD_CHARACTER·PASSWORD_LETTER_AND_DIGIT_REQUIRED. "
                    + "닉네임은 내 정보 수정과 같은 규칙. "
                    + "이메일 인증(#557)이 먼저 필요하다: 인증 코드 받기(POST /api/v1/members/email-codes) → 확인(POST /api/v1/members/email-codes/verify)으로 "
                    + "받은 emailVerificationToken을 넣는다. 없거나 만료(30분)됐거나 다른 이메일의 토큰이면 400 EMAIL_NOT_VERIFIED. "
                    + "가입되면 토큰은 사라진다(가입이 실패하면 그대로 남아 다시 쓸 수 있다).")
    RsData<MemberMeResponse> signUp(SignUpRequest request);

    @Operation(summary = "로그인",
            description = "로그인 없이 부른다. 맞으면 refresh token 쿠키(REFRESH_TOKEN)를 주고, access token은 재발급 API "
                    + "(POST /api/v1/members/auth/refresh)로 받는다 (구글 로그인과 같다). "
                    + "없는 이메일과 틀린 비밀번호는 모두 401 INVALID_CREDENTIALS. "
                    + "같은 이메일로 5번 연속 틀리면 15분 동안 429 LOGIN_LOCKED. 정지·탈퇴 회원은 403 LOGIN_NOT_ALLOWED. "
                    + "복구 기한(30일, 기한 안에 지갑 장애로 복구가 실패했으면 7일 더) 안의 탈퇴 회원(탈퇴 기능이 켜져 있을 때)은 409 WITHDRAWN_RESTORABLE과 restorableUntil을 주고, "
                    + "복구 티켓은 쿠키(RESTORE_TICKET, HttpOnly, 경로 /api/v1/members/restore, 10분)로 준다. "
                    + "\"복구할까요?\"에서 복구하면 본문 없이 POST /api/v1/members/restore를 부른다.")
    ResponseEntity<RsData<MemberMeResponse>> login(PasswordLoginRequest request,
                                                   @Parameter(hidden = true) HttpServletResponse response);

    @Operation(summary = "비밀번호 변경",
            description = "로그인한 회원 본인. 현재 비밀번호가 틀리면 400 INVALID_CURRENT_PASSWORD, "
                    + "비밀번호로 가입하지 않은 회원(구글 회원)은 409 PASSWORD_NOT_SET. 현재 비밀번호를 5번 연속 틀리면 15분 동안 429 PASSWORD_CHANGE_LOCKED. "
                    + "새 비밀번호 규칙은 가입과 같다. "
                    + "바꾸면 모든 기기의 refresh token을 폐기하고, 이 기기에는 새 refresh token 쿠키를 준다 "
                    + "(다른 기기는 access token 만료 뒤 다시 로그인).")
    ResponseEntity<RsData<Void>> changePassword(Long userId, PasswordChangeRequest request);
}
