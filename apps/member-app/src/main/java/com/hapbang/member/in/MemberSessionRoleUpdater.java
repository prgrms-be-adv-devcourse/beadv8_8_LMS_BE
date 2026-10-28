package com.hapbang.member.in;

import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;

/**
 * 회원의 모든 세션(여러 기기)의 역할을 바꾼다. 세션 저장소가 없으면(회원 모듈 단독 테스트) 아무것도 안 한다.
 * 저장소는 apps가 정한다(지금 JDBC). Redis로 바꾸면 회원 ID로 찾기를 지원하는 indexed 저장소여야 한다
 */
@Component
@RequiredArgsConstructor
public class MemberSessionRoleUpdater {

    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessionRepository;

    /** 회원 ID로 세션을 찾아, 역할이 다른 세션만 새 역할의 로그인 정보로 바꿔 저장 */
    public void update(Long userId, Role role) {
        FindByIndexNameSessionRepository<? extends Session> repository = sessionRepository.getIfAvailable();
        if (repository != null) {
            update(repository, userId, role);
        }
    }

    private <S extends Session> void update(FindByIndexNameSessionRepository<S> repository, Long userId, Role role) {
        for (S session : repository.findByPrincipalName(String.valueOf(userId)).values()) {
            SecurityContext context = session.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
            if (context != null && context.getAuthentication() instanceof OAuth2AuthenticationToken token
                    && token.getPrincipal() instanceof MemberOAuth2User user && user.getRole() != role) {
                session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, contextWith(token, user, role));
                repository.save(session);
            }
        }
    }

    /** 같은 회원 ID·속성·로그인 방식(google·local)에 역할만 바꾼 로그인 정보 */
    private SecurityContext contextWith(OAuth2AuthenticationToken token, MemberOAuth2User user, Role role) {
        MemberOAuth2User principal = new MemberOAuth2User(user.getUserId(), role, user.getAttributes());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(),
                token.getAuthorizedClientRegistrationId()));
        return context;
    }
}
