package com.hapbang.appearance.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

import com.hapbang.appearance.global.auth.GatewayUserHeaderFilter;


@Configuration
// 관리자 API 외의 역할 검사는 각 모듈 서비스 레이어(Facade)의 @PreAuthorize로 한다 (2026-10-12 회의)
@EnableMethodSecurity
public class SecurityConfig {
    // Swagger 설정을 위한 최초 생성
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {
        http
                // Gateway가 넘긴 사용자 헤더로 인증한다 (#395). 프론트는 JWT를 Authorization: Bearer로 보내고,
                // 브라우저가 자동으로 싣는 쿠키로 인증하지 않으므로 CSRF 대상이 아니다
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 세션에서 인증 정보를 읽거나 저장하지 않는다. 요청마다 헤더로 새로 만든다
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .addFilterBefore(new GatewayUserHeaderFilter(), AbstractPreAuthenticatedProcessingFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 예외가 나면 Spring이 /error로 보내는데, 막혀 있으면 원래 오류 대신 401이 나간다
                        .requestMatchers("/error").permitAll()
                        // 헬스체크 (k8s probe용). Gateway에 /actuator route가 없어 클러스터 안에서만 접근한다
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // 비회원은 강의·모집글 목록만 조회한다 (AGENTS.md 6장)
                        .requestMatchers(HttpMethod.GET, "/api/v1/lectures").permitAll()
                        // 관리자 API는 모든 모듈이 /api/v1/{module}/admin/** 아래에 둔다 (2026-10-12 회의)
                        .requestMatchers("/api/v1/*/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                // 로그인·로그아웃은 회원 앱의 책임이다. 비로그인 요청은 401
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // 비로그인 요청을 세션에 저장하지 않는다 (SPA라 원래 페이지 복귀는 프론트가 처리)
                .requestCache(cache -> cache.disable());
        return http.build();
    }
}
