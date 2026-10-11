package com.hapbang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfig {
    // Swagger 설정을 위한 최초 생성
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 예외가 나면 Spring이 /error로 보내는데, 막혀 있으면 원래 오류 대신 401이 나간다
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                // 구글 로그인 시작: /oauth2/authorization/google (회원 처리는 member의 OAuth2UserService 빈)
                .oauth2Login(Customizer.withDefaults())
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/v1/members/logout"))
                        // 리다이렉트 없이 200만 응답 (프론트가 화면 이동 처리)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler())
                        .deleteCookies("JSESSIONID"))
                // 비로그인 요청은 구글 로그인 화면으로 리다이렉트하지 않고 401
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
