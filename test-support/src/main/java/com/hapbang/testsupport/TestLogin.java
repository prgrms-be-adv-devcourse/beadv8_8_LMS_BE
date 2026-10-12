package com.hapbang.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

import com.hapbang.shared.member.domain.Role;

/** 테스트에서 로그인 상태 만들기 (실제 구글 호출 없음). 예: .with(TestLogin.as(1L, Role.USER)) */
public final class TestLogin {

    private TestLogin() {
    }

    /** 이 회원 ID·역할로 로그인한 요청. @LoginUserId에는 userId가 들어간다 */
    public static OAuth2LoginRequestPostProcessor as(Long userId, Role role) {
        // getName()이 회원 ID가 되도록 "id" 속성을 이름 속성으로 지정한다
        OAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())),
                Map.of("id", userId),
                "id");
        return oauth2Login().oauth2User(user);
    }
}
