package com.hapbang.auxiliary.global.config;

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

import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;


@Configuration
// 관리자 API 외의 역할 검사는 각 모듈 서비스 레이어(Facade)의 @PreAuthorize로 한다 (2026-10-12 회의)
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {
        http
                // Gateway가 넘긴 사용자 헤더로 인증한다(다른 앱과 같은 방식). 프론트는 access token을
                // Authorization: Bearer로 보내고, 브라우저가 자동으로 싣는 쿠키로 인증하지 않으므로 CSRF 대상이 아니다
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 세션에서 인증 정보를 읽거나 저장하지 않는다. 요청마다 헤더로 새로 만든다
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .addFilterBefore(new GatewayUserHeaderFilter(), AbstractPreAuthenticatedProcessingFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Swagger는 모듈 경로 아래에 있다 (swagger.yml). 운영에서도 누구나 본다
                        .requestMatchers(HttpMethod.GET, "/api/v1/auxiliary/swagger-ui.html",
                                "/api/v1/auxiliary/swagger-ui/**", "/api/v1/auxiliary/v3/api-docs/**").permitAll()
                        // 예외가 나면 Spring이 /error로 보내는데, 막혀 있으면 원래 오류 대신 401이 나간다
                        .requestMatchers("/error").permitAll()
                        // 헬스체크(k8s probe)와 Prometheus 수집(#590). k8s에서는 관리 포트(MANAGEMENT_SERVER_PORT)에서만 열리고
                        // NetworkPolicy가 그 포트에 Prometheus만 들인다. 외부 API 포트는 Gateway 전용이다 (D72). 로컬은 앱 포트에서 열린다
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        // 관리자 API는 모든 모듈이 /api/v1/{module}/admin/** 아래에 둔다 (2026-10-12 회의)
                        .requestMatchers("/api/v1/*/admin/**").hasRole("ADMIN")
                        // 알림 웹소켓 연결(핸드셰이크)만 연다. 브라우저는 업그레이드 요청에 Authorization을 싣지 못해 사용자 헤더가 없다.
                        // 인증은 알림 모듈이 일회용 연결 티켓으로 한다(docs/notification/decisions.md ND-060). 다른 알림 경로는 로그인이 필요하다
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications/ws").permitAll()
                        // 강의 검색은 비회원도 쓴다(policy.md 강의 목록 비회원 O, #883). 회원이면 헤더로 인증되어 회원 한도로 센다
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/lectures").permitAll()
                        // 업무 API는 비회원에게 열지 않는다. 역할 검사는 각 모듈 Facade의 @PreAuthorize로 한다(챗봇은 로그인 회원 전체)
                        .anyRequest().authenticated()
                )
                // 로그인·로그아웃은 회원 앱의 책임이다. 비로그인 요청은 401
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // 비로그인 요청을 저장하지 않는다 (SPA라 원래 페이지 복귀는 프론트가 처리)
                .requestCache(cache -> cache.disable());
        return http.build();
    }
}
