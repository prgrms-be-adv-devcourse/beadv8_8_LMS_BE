package com.hapbang.billing.global.ratelimit;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * {@link RateLimited}가 붙은 컨트롤러 메서드를 부르기 전에 회원별 요청 한도를 확인한다 (#627, BD-140).
 * <p>
 * 인증 필터 뒤, 컨트롤러 앞에서 돈다. 넘으면 {@link RateLimitExceededException}을 던지고 공통 핸들러가 429로 응답한다.
 * 키는 Gateway가 넘긴 회원 ID({@code X-User-Id}, 인증 이름)다. 대상 API는 모두 로그인이 필요해 보안 설정이 비로그인을 먼저
 * 401로 막지만, 로그인 정보가 없으면 세지 않고 넘긴다.
 */
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final UserRateLimiter userRateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited rateLimited = handlerMethod.getMethodAnnotation(RateLimited.class);
        if (rateLimited == null) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return true;
        }
        userRateLimiter.acquire(Long.parseLong(authentication.getName()), rateLimited.value());
        return true;
    }
}
