package com.hapbang.billing.in;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import com.hapbang.billing.out.ConditionalOnMockPayout;
import com.hapbang.billing.out.MockPayoutAccessKey;

// 가상 송금 Mock 은행 API 경로만의 보안 설정 (#288, Mock이 켜졌을 때만). global SecurityConfig보다 먼저 적용한다.
// 같은 애플리케이션의 HTTP Client가 세션·CSRF 토큰 없이 부르므로 이 경로는 로그인·CSRF 검사를 빼고 세션을 만들지 않는다.
// 대신 앱이 뜰 때 만든 접근 키(X-Mock-Payout-Key)가 맞는 요청만 통과시킨다(아니면 컨트롤러 전에 본문 없는 403).
// 꺼져 있으면 이 체인이 없어 global 설정(로그인 필요)을 따르고 컨트롤러도 없다.
@Configuration(proxyBeanMethods = false)
@ConditionalOnMockPayout
class MockPayoutSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain mockPayoutFilterChain(HttpSecurity http, MockPayoutAccessKey accessKey) throws Exception {
        http.securityMatcher(MockPayoutController.PATH, MockPayoutController.PATH + "/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) ->
                        new AuthorizationDecision(accessKey.matches(
                                context.getRequest().getHeader(MockPayoutAccessKey.HEADER)))))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN))
                        .accessDeniedHandler((request, response, denied) ->
                                response.sendError(HttpStatus.FORBIDDEN.value())));
        return http.build();
    }
}
