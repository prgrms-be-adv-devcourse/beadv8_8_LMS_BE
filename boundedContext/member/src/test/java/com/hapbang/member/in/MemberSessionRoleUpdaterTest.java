package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;

import com.hapbang.shared.member.domain.Role;

/** 세션 저장소는 Mock: 회원 ID로 찾고, 역할이 다른 세션만 바꿔 저장하는지 확인한다 (실제 JDBC 동작은 apps 테스트) */
@ExtendWith(MockitoExtension.class)
class MemberSessionRoleUpdaterTest {

    private static final Long USER_ID = 3L;

    @Mock
    ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessionRepositoryProvider;

    @Mock
    FindByIndexNameSessionRepository<MapSession> sessionRepository;

    @Test
    @DisplayName("회원의 세션(PC·폰) 모두 같은 회원 ID·로그인 방식에 새 역할로 바뀌어 저장된다")
    void update_allSessions() {
        MapSession pc = loggedIn(Role.USER, "google");
        MapSession phone = loggedIn(Role.USER, "google");
        givenSessions(pc, phone);

        updater().update(USER_ID, Role.INFLUENCER);

        for (MapSession session : new MapSession[] {pc, phone}) {
            OAuth2AuthenticationToken token = tokenOf(session);
            MemberOAuth2User user = (MemberOAuth2User) token.getPrincipal();
            assertThat(user.getUserId()).isEqualTo(USER_ID);
            assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
            assertThat(token.getAuthorities()).extracting("authority").containsExactly("ROLE_INFLUENCER");
            assertThat(token.getAuthorizedClientRegistrationId()).isEqualTo("google");
            verify(sessionRepository).save(session);
        }
    }

    @Test
    @DisplayName("역할이 이미 같은 세션은 저장하지 않는다 (닉네임 변경 이벤트 등)")
    void update_sameRole() {
        givenSessions(loggedIn(Role.INFLUENCER, "local"));

        updater().update(USER_ID, Role.INFLUENCER);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("로그인 정보가 없는 세션(로그인 도중)은 건너뛴다")
    void update_notLoggedIn() {
        givenSessions(new MapSession());

        updater().update(USER_ID, Role.INFLUENCER);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("세션 저장소가 없으면(회원 모듈 단독) 아무것도 하지 않는다")
    void update_withoutRepository() {
        given(sessionRepositoryProvider.getIfAvailable()).willReturn(null);

        updater().update(USER_ID, Role.INFLUENCER);

        verify(sessionRepository, never()).findByPrincipalName(any());
    }

    private MemberSessionRoleUpdater updater() {
        return new MemberSessionRoleUpdater(sessionRepositoryProvider);
    }

    private void givenSessions(MapSession... sessions) {
        doReturn(sessionRepository).when(sessionRepositoryProvider).getIfAvailable();
        Map<String, MapSession> byId = new HashMap<>();
        for (MapSession session : sessions) {
            byId.put(session.getId(), session);
        }
        given(sessionRepository.findByPrincipalName(String.valueOf(USER_ID))).willReturn(byId);
    }

    /** 로그인된 세션: 실제 로그인과 같은 모양(OAuth2AuthenticationToken + MemberOAuth2User) */
    private MapSession loggedIn(Role role, String registrationId) {
        MemberOAuth2User user = new MemberOAuth2User(USER_ID, role, Map.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), registrationId));
        MapSession session = new MapSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private OAuth2AuthenticationToken tokenOf(MapSession session) {
        SecurityContext context = session.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
        return (OAuth2AuthenticationToken) context.getAuthentication();
    }
}
