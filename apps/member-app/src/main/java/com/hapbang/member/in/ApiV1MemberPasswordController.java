package com.hapbang.member.in;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.app.TokenPair;
import com.hapbang.member.domain.EmailVerificationPolicy;
import com.hapbang.member.domain.User;
import com.hapbang.member.domain.WithdrawnRestorableException;
import com.hapbang.member.global.auth.LoginUserId;
import com.hapbang.member.global.response.RsData;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 이메일·비밀번호 가입·로그인·비밀번호 변경 API (#499).
 * 로그인은 구글 로그인과 같이 refresh token 쿠키만 주고, 프론트는 재발급 API로 access token을 받는다
 */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class ApiV1MemberPasswordController implements ApiV1MemberPasswordSpec {

    private final MemberFacade memberFacade;
    private final RefreshTokenCookies refreshTokenCookies;
    private final RestoreTicketCookies restoreTicketCookies;

    /**
     * 인증 코드 받기 (#557, 가입·비밀번호 재설정). IP당 발송 제한에 쓰는 접속 IP는 Gateway가 붙인 X-Forwarded-For를 Tomcat이 풀어 준 값이다
     * (운영 server.forward-headers-strategy=native, docs/architecture/infrastructure/api-gateway.md)
     */
    @Override
    @PostMapping("/email-codes")
    public RsData<EmailCodeResponse> sendEmailCode(@Valid @RequestBody EmailCodeRequest request,
                                                   HttpServletRequest httpRequest) {
        memberFacade.sendEmailCode(request.email(), request.purpose(), httpRequest.getRemoteAddr());
        return RsData.ok("인증 코드를 보냈습니다.", new EmailCodeResponse(
                EmailVerificationPolicy.CODE_TTL.toSeconds(), EmailVerificationPolicy.RESEND_COOLDOWN.toSeconds()));
    }

    /** 인증 코드 확인 (#557). 맞으면 그 용도(가입·재설정)에 쓸 인증 토큰을 준다 */
    @Override
    @PostMapping("/email-codes/verify")
    public RsData<EmailVerificationTokenResponse> verifyEmailCode(@Valid @RequestBody EmailCodeVerifyRequest request) {
        String token = memberFacade.verifyEmailCode(request.email(), request.purpose(), request.code());
        return RsData.ok("이메일을 인증했습니다.",
                new EmailVerificationTokenResponse(token, EmailVerificationPolicy.TOKEN_TTL.toSeconds()));
    }

    /** 가입. 로그인은 따로 한다 */
    @Override
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<MemberMeResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        User user = memberFacade.signUp(request.email(), request.password(), request.nickname(),
                request.emailVerificationToken());
        return RsData.created("가입했습니다.", meResponse(user));
    }

    /**
     * 로그인: refresh token 쿠키. 복구할 수 있는 탈퇴 회원이면 복구 티켓 쿠키를 붙이고 409는 공통 예외 처리에 맡긴다
     * (본문에는 복구 기한만, #633). 로그인에 성공하면 앞사람이 남긴 복구 티켓 쿠키를 지운다(공용 PC)
     */
    @Override
    @PostMapping("/login")
    public ResponseEntity<RsData<MemberMeResponse>> login(@Valid @RequestBody PasswordLoginRequest request,
                                                          HttpServletResponse response) {
        User user;
        try {
            user = memberFacade.passwordLogin(request.email(), request.password());
        } catch (WithdrawnRestorableException e) {
            response.addHeader(HttpHeaders.SET_COOKIE, restoreTicketCookies.issue(e.getRestoreTicket()).toString());
            throw e;
        }
        TokenPair tokens = memberFacade.issueTokens(user.getId(), user.getRole());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshTokenCookies.issue(tokens.refreshToken()).toString(),
                        restoreTicketCookies.expire().toString())
                .body(RsData.ok("로그인했습니다.", meResponse(user)));
    }

    /** 비밀번호 변경: 다른 기기는 로그아웃되고, 이 기기는 새 refresh token 쿠키를 받는다 */
    @Override
    @PatchMapping("/me/password")
    public ResponseEntity<RsData<Void>> changePassword(@LoginUserId Long userId,
                                                       @Valid @RequestBody PasswordChangeRequest request) {
        TokenPair tokens = memberFacade.changePassword(userId, request.currentPassword(), request.newPassword());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(tokens.refreshToken()).toString())
                .body(RsData.ok("비밀번호를 바꿨습니다.", null));
    }

    private MemberMeResponse meResponse(User user) {
        return MemberMeResponse.from(user, memberFacade.profileImageUrl(user.getProfileImageKey()));
    }
}
