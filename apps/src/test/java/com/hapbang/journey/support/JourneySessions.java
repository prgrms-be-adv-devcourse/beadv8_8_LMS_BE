package com.hapbang.journey.support;

import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import com.hapbang.member.in.MemberOAuth2User;
import com.hapbang.shared.member.domain.Role;

import jakarta.servlet.http.Cookie;

/**
 * 실제 구글 로그인과 같은 JDBC 세션을 만들고 그 {@code SESSION} 쿠키로 요청한다.
 * {@code TestLogin}은 세션을 저장하지 않고 principal도 {@code MemberOAuth2User}가 아니어서, 승인 뒤 저장된 세션 권한이
 * 바뀌는 것(MemberSessionRoleUpdater)을 볼 수 없기 때문이다. 테이블 정리는 JPA 테이블만 비우므로 만든 세션은 {@link #deleteAll}로 지운다.
 */
public class JourneySessions {

    private final FindByIndexNameSessionRepository<? extends Session> repository;
    private final List<String> createdIds = new ArrayList<>();

    public JourneySessions(FindByIndexNameSessionRepository<? extends Session> repository) {
        this.repository = repository;
    }

    /** 로그인한 세션을 저장하고 세션 ID를 돌려준다 (principal_name이 회원 ID로 기록된다) */
    public String login(Long userId, Role role) {
        return save(repository, userId, role);
    }

    private <S extends Session> String save(FindByIndexNameSessionRepository<S> sessions, Long userId, Role role) {
        MemberOAuth2User principal = new MemberOAuth2User(userId, role, Map.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        S session = sessions.createSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
        sessions.save(session);
        createdIds.add(session.getId());
        return session.getId();
    }

    /** 브라우저가 보내는 세션 쿠키 (Spring Session 기본 쿠키는 세션 ID를 Base64로 담는다) */
    public static Cookie cookie(String sessionId) {
        return new Cookie("SESSION", Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
    }

    /** DB에서 새로 읽은 세션의 역할. 세션이 없으면 null */
    public Role roleOf(String sessionId) {
        Session session = repository.findById(sessionId);
        if (session == null) {
            return null;
        }
        SecurityContext context = session.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
        return ((MemberOAuth2User) context.getAuthentication().getPrincipal()).getRole();
    }

    /** DB에서 새로 읽은 세션 속성 */
    public <T> T attributeOf(String sessionId, String name) {
        Session session = repository.findById(sessionId);
        return session == null ? null : session.getAttribute(name);
    }

    public boolean exists(String sessionId) {
        return repository.findById(sessionId) != null;
    }

    public void deleteAll() {
        createdIds.forEach(repository::deleteById);
        createdIds.clear();
    }
}
