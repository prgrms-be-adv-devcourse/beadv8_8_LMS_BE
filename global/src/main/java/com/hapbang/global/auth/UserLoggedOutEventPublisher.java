package com.hapbang.global.auth;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

import com.hapbang.shared.member.event.UserLoggedOutEvent;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * 로그아웃할 때 {@link UserLoggedOutEvent}를 발행한다 (#242). 세션을 무효화하는 기본 처리기보다 먼저 실행되므로
 * 끝나는 세션 ID를 담을 수 있다. 로그인하지 않았거나 세션이 없으면 닫을 연결이 없으니 발행하지 않는다.
 */
@RequiredArgsConstructor
public class UserLoggedOutEventPublisher implements LogoutHandler {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (authentication == null || session == null) {
            return;
        }
        // getName()은 회원 ID (member의 MemberOAuth2User)
        eventPublisher.publishEvent(new UserLoggedOutEvent(Long.valueOf(authentication.getName()), session.getId()));
    }
}
