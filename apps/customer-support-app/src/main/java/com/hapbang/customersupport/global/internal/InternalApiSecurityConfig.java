package com.hapbang.customersupport.global.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * 모듈 간 내부 HTTP API(`/api/v1/internal/**`) 보안 (#300). 회원 API 체인({@code SecurityConfig})보다 먼저 고른다.
 * <ul>
 *     <li>{@code X-Internal-Api-Key}가 맞는 요청만 통과한다. 없거나 틀리면 401(본문 없음)이다.</li>
 *     <li>회원 세션을 읽지 않는다. 로그인한 회원도 키가 없으면 401이다.</li>
 *     <li>서버 nginx는 이 경로를 밖에서 404로 끊고, 내부 호출은 nginx를 거치지 않는다 (docs/deploy).</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(InternalApiProperties.class)
public class InternalApiSecurityConfig {

    static final String PATH = "/api/v1/internal/**";

    @Bean
    @Order(1)
    SecurityFilterChain internalApiSecurityFilterChain(HttpSecurity http, InternalApiProperties properties)
            throws Exception {
        HttpStatusEntryPoint unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        http
                .securityMatcher(PATH)
                // 쿠키 세션 인증이 아니라 CSRF 대상이 아니다
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 회원 세션의 인증 정보를 불러오지 않는다
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(new InternalApiKeyFilter(properties.key()),
                        AbstractPreAuthenticatedProcessingFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(InternalApiKeyFilter.ROLE))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((request, response, denied) ->
                                response.setStatus(HttpStatus.UNAUTHORIZED.value())));
        return http.build();
    }
}
