package com.hapbang.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.TestExecutionListeners;

import com.hapbang.member.app.MemberChannelReviewFacade;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.member.in.MemberOAuth2User;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/** 진짜 JDBC 세션: 관리자 승인 → 커밋 → 리스너 → spring_session에 저장된 그 회원의 세션 역할이 바뀐다 */
@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@WithMockUser(roles = "ADMIN")
class MemberSessionRoleIntegrationTest {

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    MemberChannelReviewFacade memberChannelReviewFacade;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    /** apps의 JDBC 세션 저장소 */
    @Autowired
    FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    private final List<String> createdSessionIds = new ArrayList<>();

    @Test
    @DisplayName("관리자가 승인하면 그 회원의 저장된 세션(PC·폰)이 모두 인플루언서 권한으로 바뀐다")
    void approve_updatesStoredSessions() {
        User user = memberFacade.oauthLogin(OAuthProvider.GOOGLE, "session-role-sub",
                "session-role@example.com", "세션회원");
        String pc = saveLoggedInSession(sessionRepository, user.getId(), Role.USER);
        String phone = saveLoggedInSession(sessionRepository, user.getId(), Role.USER);
        Long channelId = pendingChannel(user);

        memberChannelReviewFacade.approve(99L, channelId);

        assertThat(roleInSession(pc)).isEqualTo(Role.INFLUENCER);
        assertThat(roleInSession(phone)).isEqualTo(Role.INFLUENCER);
    }

    /** 테이블 정리(DatabaseCleanUp)는 JPA 테이블만 비워서 세션은 직접 지운다 */
    @AfterEach
    void deleteSessions() {
        createdSessionIds.forEach(sessionRepository::deleteById);
    }

    /** 실제 로그인처럼 시큐리티 컨텍스트를 넣어 저장 → 저장소가 principal_name을 회원 ID로 기록한다 */
    private <S extends Session> String saveLoggedInSession(FindByIndexNameSessionRepository<S> repository,
                                                           Long userId, Role role) {
        MemberOAuth2User principal = new MemberOAuth2User(userId, role, Map.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        S session = repository.createSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        createdSessionIds.add(session.getId());
        return session.getId();
    }

    /** DB에서 새로 읽은 세션의 역할 */
    private Role roleInSession(String sessionId) {
        SecurityContext context = sessionRepository.findById(sessionId).getAttribute(SPRING_SECURITY_CONTEXT_KEY);
        return ((MemberOAuth2User) context.getAuthentication().getPrincipal()).getRole();
    }

    private Long pendingChannel(User user) {
        LocalDateTime now = LocalDateTime.now();
        InfluencerChannel channel = InfluencerChannel.register(user, ChannelPlatform.CHZZK, "session-role-channel",
                "테스트 채널", 5, now);
        channel.requestApproval(5, now);
        return influencerChannelRepository.save(channel).getId();
    }
}
