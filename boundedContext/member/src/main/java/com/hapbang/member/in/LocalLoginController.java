package com.hapbang.member.in;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 로컬 로그인 (구글 없이 역할로 로그인). hapbang.local-login.enabled=true일 때만 생긴다 */
@RestController
@ConditionalOnProperty(name = "hapbang.local-login.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LocalLoginController {

    private final MemberFacade memberFacade;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    /** 예: POST /api/v1/members/local-login?role=INFLUENCER&number=2 (number는 같은 역할의 몇 번째 계정, 생략하면 1) */
    @PostMapping("/api/v1/members/local-login")
    public MemberMeResponse login(@RequestParam(name = "role") Role role,
                                  @RequestParam(name = "number", defaultValue = "1") int number,
                                  HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // 테스트 계정은 앱 시작 시 MemberDataInit이 만든다
        User user = memberFacade.findTestAccount(role, number)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "테스트 계정이 없습니다."));

        // 구글 로그인처럼 기존 세션 ID를 바꾼다 (세션 고정 공격 방지)
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        // 구글 로그인과 같은 형태(MemberOAuth2User)로 세션에 저장한다
        MemberOAuth2User principal = new MemberOAuth2User(user.getId(), user.getRole(), Map.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "local"));
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return MemberMeResponse.from(user);
    }
}
