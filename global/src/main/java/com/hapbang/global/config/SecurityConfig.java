package com.hapbang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
// 관리자 API 외의 역할 검사는 각 모듈 서비스 레이어(Facade)의 @PreAuthorize로 한다 (2026-10-12 회의)
@EnableMethodSecurity
public class SecurityConfig {
    // Swagger 설정을 위한 최초 생성
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 세션 쿠키 인증이라 CSRF를 막는다. XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내야 통과
                .csrf(csrf -> csrf.spa())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 예외가 나면 Spring이 /error로 보내는데, 막혀 있으면 원래 오류 대신 401이 나간다
                        .requestMatchers("/error").permitAll()
                        // 로컬 로그인 (로컬에서만 켜짐. 서버엔 컨트롤러가 없어 404)
                        .requestMatchers(HttpMethod.POST, "/api/v1/members/local-login").permitAll()
                        // 비회원은 강의·모집글 목록만 조회한다 (AGENTS.md 6장)
                        .requestMatchers(HttpMethod.GET, "/api/v1/lectures").permitAll()
                        // 관리자 API는 모든 모듈이 /api/v1/{module}/admin/** 아래에 둔다 (2026-10-12 회의)
                        .requestMatchers("/api/v1/*/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                // 구글 로그인 시작: /api/v1/members/oauth2/authorization/google (회원 처리는 member의 OAuth2UserService 빈)
                // 서버 nginx는 /api/만 Spring으로 보내므로 로그인·콜백 주소도 /api/ 아래에 둔다
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(a -> a.baseUri("/api/v1/members/oauth2/authorization"))
                        .redirectionEndpoint(r -> r.baseUri("/api/v1/members/login/oauth2/code/*")))
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/v1/members/logout"))
                        // 리다이렉트 없이 200만 응답 (프론트가 화면 이동 처리)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler())
                        .deleteCookies("SESSION"))
                // 비로그인 요청은 구글 로그인 화면으로 리다이렉트하지 않고 401
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // 비로그인 요청을 세션에 저장하지 않는다 (SPA라 원래 페이지 복귀는 프론트가 처리, 불필요한 DB 세션 방지)
                .requestCache(cache -> cache.disable());
        return http.build();
    }
}
