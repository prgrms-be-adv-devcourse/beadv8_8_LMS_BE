package com.hapbang.member.in;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.global.response.RsData;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.app.TokenPair;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 로컬 로그인 (구글 없이 역할로 로그인). hapbang.local-login.enabled=true일 때만 생긴다 (local·dev, 운영은 꺼짐).
 * 구글 로그인과 같이 refresh token 쿠키를 주고, 프론트는 재발급 API로 access token을 받는다 (#370)
 */
@RestController
@ConditionalOnProperty(name = "hapbang.local-login.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LocalLoginController {

    private final MemberFacade memberFacade;
    private final RefreshTokenCookies refreshTokenCookies;

    /** 예: POST /api/v1/members/local-login?role=INFLUENCER&number=2 (number는 같은 역할의 몇 번째 계정, 생략하면 1) */
    @PostMapping("/api/v1/members/local-login")
    public RsData<MemberMeResponse> login(@RequestParam(name = "role") Role role,
                                  @RequestParam(name = "number", defaultValue = "1") int number,
                                  HttpServletResponse httpResponse) {
        // 테스트 계정은 앱 시작 시 MemberDataInit이 만든다
        User user = memberFacade.findTestAccount(role, number)
                .orElseThrow(() -> new MemberException(MemberErrorCode.TEST_ACCOUNT_NOT_FOUND));

        // 구글 로그인과 같이 refresh token을 쿠키로 준다 (#370). access token은 프론트가 재발급 API로 받는다
        TokenPair tokens = memberFacade.issueTokens(user.getId(), user.getRole());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(tokens.refreshToken()).toString());

        return RsData.ok("테스트 계정으로 로그인했습니다.", MemberMeResponse.from(user, memberFacade.profileImageUrl(user.getProfileImageKey())));
    }
}
