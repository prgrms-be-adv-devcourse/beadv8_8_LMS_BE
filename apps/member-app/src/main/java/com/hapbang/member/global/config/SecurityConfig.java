package com.hapbang.member.global.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

import com.hapbang.member.global.auth.GatewayUserHeaderFilter;
import com.hapbang.member.in.NoOpOAuth2AuthorizedClientRepository;
import com.hapbang.member.in.OAuthLoginFailureHandler;
import com.hapbang.member.in.OAuthLoginSuccessHandler;
import com.hapbang.member.in.RedisOAuth2AuthorizationRequestRepository;

@Configuration
// 관리자 API 외의 역할 검사는 각 모듈 서비스 레이어(Facade)의 @PreAuthorize로 한다 (2026-10-12 회의)
@EnableMethodSecurity
public class SecurityConfig {
    // Swagger 설정을 위한 최초 생성
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ObjectProvider<RedisOAuth2AuthorizationRequestRepository> authorizationRequestRepository,
                                                   ObjectProvider<OAuthLoginSuccessHandler> oauthLoginSuccessHandler,
                                                   ObjectProvider<OAuthLoginFailureHandler> oauthLoginFailureHandler)
            throws Exception {
        http
                // Gateway가 넘긴 사용자 헤더로 인증한다 (#370, 출연 #395와 같은 방식). 프론트는 access token을
                // Authorization: Bearer로 보내고, 브라우저가 자동으로 싣는 쿠키로 인증하지 않으므로 CSRF 대상이 아니다.
                // refresh token 쿠키는 SameSite=Strict라 다른 사이트에서 시작한 요청에 붙지 않는다
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 세션에서 인증 정보를 읽거나 저장하지 않는다. 요청마다 헤더로 새로 만든다
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .addFilterBefore(new GatewayUserHeaderFilter(), AbstractPreAuthenticatedProcessingFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Swagger는 모듈 경로 아래에 있다 (swagger.yml, #498). 운영에서도 누구나 본다
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/swagger-ui.html",
                                "/api/v1/members/swagger-ui/**", "/api/v1/members/v3/api-docs/**").permitAll()
                        // 예외가 나면 Spring이 /error로 보내는데, 막혀 있으면 원래 오류 대신 401이 나간다
                        .requestMatchers("/error").permitAll()
                        // 헬스체크 (k8s probe용). Gateway에 /actuator route가 없어 클러스터 안에서만 접근한다
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // Prometheus scrape. Gateway가 /actuator를 라우팅하지 않고 NetworkPolicy가 Prometheus pod만 허용한다 (#480)
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        // JWT 공개 키 목록 (Envoy가 받는다, #370). 경로는 Envoy remoteJWKS 주소와 같다 (#372)
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/.well-known/jwks.json").permitAll()
                        // 치지직 연동 콜백: 브라우저 이동이라 로그인 정보가 없다. 시작 때 맡긴 회원으로 처리한다 (#370)
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/channels/chzzk/callback").permitAll()
                        // 재발급·로그아웃 (access token이 만료된 상태에서 refresh token 쿠키로 부른다, #370)
                        .requestMatchers(HttpMethod.POST, "/api/v1/members/auth/refresh", "/api/v1/members/auth/logout")
                        .permitAll()
                        // 이메일·비밀번호 가입·로그인 (#499). 로그인 응답은 refresh token 쿠키만 준다
                        .requestMatchers(HttpMethod.POST, "/api/v1/members/signup", "/api/v1/members/login").permitAll()
                        // 비회원은 강의·모집글 목록만 조회한다 (AGENTS.md 6장)
                        .requestMatchers(HttpMethod.GET, "/api/v1/lectures").permitAll()
                        // 관리자 API는 모든 모듈이 /api/v1/{module}/admin/** 아래에 둔다 (2026-10-12 회의)
                        .requestMatchers("/api/v1/*/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                // 구글 로그인 시작: /api/v1/members/oauth2/authorization/google (회원 처리는 member의 OAuth2UserService 빈)
                // Gateway는 /api/만 앱으로 보내므로 로그인·콜백 주소도 /api/ 아래에 둔다
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(a -> {
                            a.baseUri("/api/v1/members/oauth2/authorization");
                            // 로그인 진행 상태(state·PKCE)는 세션 대신 Redis에 둔다 (#370).
                            // 빈이 없는 웹 슬라이스 테스트(Redis 없음)에서는 기본 저장소를 쓴다
                            authorizationRequestRepository.ifAvailable(a::authorizationRequestRepository);
                        })
                        .redirectionEndpoint(r -> r.baseUri("/api/v1/members/login/oauth2/code/*"))
                        // 로그인 뒤 구글 API를 부르지 않으므로 구글 토큰을 보관하지 않는다
                        .authorizedClientRepository(new NoOpOAuth2AuthorizedClientRepository())
                        // 성공하면 refresh token 쿠키를 주고 프론트로 보낸다 (access token은 프론트가 재발급 API로 받는다)
                        .successHandler(loginSuccessHandler(oauthLoginSuccessHandler))
                        // 실패하면 프론트로 보내면서 이유를 error로 붙인다 (#499)
                        .failureHandler(loginFailureHandler(oauthLoginFailureHandler)))
                // 로그아웃은 토큰 API(POST /api/v1/members/auth/logout)가 맡는다. 비로그인 요청은 구글 로그인 화면으로
                // 리다이렉트하지 않고 401
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // 비로그인 요청을 저장하지 않는다 (SPA라 원래 페이지 복귀는 프론트가 처리)
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    /** 빈이 없는 웹 슬라이스 테스트에서는 Spring 기본 성공 처리(원래 페이지 또는 "/"로 이동)를 쓴다 */
    private static AuthenticationSuccessHandler loginSuccessHandler(
            ObjectProvider<OAuthLoginSuccessHandler> oauthLoginSuccessHandler) {
        AuthenticationSuccessHandler handler = oauthLoginSuccessHandler.getIfAvailable();
        return handler != null ? handler : new SavedRequestAwareAuthenticationSuccessHandler();
    }

    /** 빈이 없는 웹 슬라이스 테스트에서는 Spring 기본 실패 처리(/login?error로 이동)를 쓴다 */
    private static AuthenticationFailureHandler loginFailureHandler(
            ObjectProvider<OAuthLoginFailureHandler> oauthLoginFailureHandler) {
        AuthenticationFailureHandler handler = oauthLoginFailureHandler.getIfAvailable();
        return handler != null ? handler : new SimpleUrlAuthenticationFailureHandler("/login?error");
    }
}
