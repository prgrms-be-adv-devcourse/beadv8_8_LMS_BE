package com.hapbang.billing.global.auth;

import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 세션의 로그인 정보에서 회원 ID를 꺼내 @LoginUserId 파라미터에 넣는다 */
public class LoginUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(LoginUserId.class)
                && parameter.getParameterType().equals(Long.class);
    }

    /** getName()은 회원 ID (member의 MemberOAuth2User) */
    @Override
    public Long resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            // 공개 URL에서 쓰인 경우. Spring Security가 401로 바꿔 응답한다
            throw new AuthenticationCredentialsNotFoundException("로그인이 필요합니다.");
        }
        return Long.valueOf(authentication.getName());
    }
}
