package com.hapbang.member.in;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.app.TokenPair;
import com.hapbang.member.global.config.JwtProperties;
import com.hapbang.member.global.response.RsData;

import lombok.RequiredArgsConstructor;

/** 인증 API (#370): refresh token 쿠키로 재발급·로그아웃. 경로가 refresh token 쿠키의 Path 아래라 쿠키가 자동으로 붙는다 */
@RestController
@RequestMapping(RefreshTokenCookies.PATH)
@RequiredArgsConstructor
public class ApiV1MemberAuthController implements ApiV1MemberAuthSpec {

    private final MemberFacade memberFacade;
    private final RefreshTokenCookies refreshTokenCookies;
    private final JwtProperties jwtProperties;

    /** 재발급: access token은 본문, 바뀐 refresh token은 쿠키 */
    @Override
    @PostMapping("/refresh")
    public ResponseEntity<RsData<AccessTokenResponse>> refresh(
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken) {
        TokenPair tokens = memberFacade.refreshTokens(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(tokens.refreshToken()).toString())
                .body(RsData.ok("토큰을 다시 발급했습니다.",
                        AccessTokenResponse.of(tokens.accessToken(), jwtProperties.accessTokenTtl())));
    }

    /** 로그아웃: 쿠키가 없어도 성공하고, 쿠키는 항상 지운다 */
    @Override
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken) {
        memberFacade.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.expire().toString())
                .build();
    }
}
