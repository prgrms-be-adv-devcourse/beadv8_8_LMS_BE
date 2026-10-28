package com.hapbang.chat.in.support;

import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.shared.member.domain.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * E2E 테스트 전용 로그인. 실제 구글을 호출하지 않고, 회원 모듈의 구글 로그인과 같은 형태의 세션을 만든다.
 * <ul>
 *     <li>OAuth2 로그인 사용자({@code getName()} = 회원 ID)를 세션에 저장한다. {@code @LoginUserId}와 웹소켓 핸드셰이크가 이 값을 쓴다.</li>
 *     <li>이 경로만 열어 두는 보안 설정을 global SecurityConfig보다 먼저 적용한다. 나머지 요청은 운영과 같은 보안 설정을 거친다.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class E2ELoginConfig {

    public static final String LOGIN_PATH = "/test/chat/session-login";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain testSessionLoginFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(LOGIN_PATH)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * {@code @TestComponent}라서 다른 테스트의 컴포넌트 스캔에는 잡히지 않는다.
     */
    @TestComponent
    @RestController
    public static class TestSessionLoginController {

        @PostMapping(LOGIN_PATH)
        ResponseEntity<Void> login(@RequestParam("userId") Long userId, @RequestParam("role") Role role,
                HttpServletRequest request, HttpServletResponse response) {
            DefaultOAuth2User user = new DefaultOAuth2User(
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.name())),
                    Map.of("id", userId), "id");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);
            return ResponseEntity.noContent().build();
        }
    }
}
