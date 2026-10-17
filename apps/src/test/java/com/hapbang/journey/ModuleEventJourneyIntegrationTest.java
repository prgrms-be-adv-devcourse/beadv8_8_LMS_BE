package com.hapbang.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.billing.out.BillingUserRepository;
import com.hapbang.chat.in.event.ChatLogoutEventListener;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.journey.support.JourneyFixtures;
import com.hapbang.journey.support.JourneySessions;
import com.hapbang.journey.support.JourneyTest;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserModifiedEvent;

/**
 * 시나리오 4 — 모듈 간 이벤트 경계 (#326).
 * <ul>
 *     <li>회원 정지: 회원 모듈에 정지 기능(상태를 바꾸고 UserModifiedEvent를 발행하는 곳)이 아직 없어, 회원 모듈이 발행할 이벤트를
 *     그대로 발행해 받는 쪽(chat·lecture·appearance·billing 복제본)만 본다.</li>
 *     <li>로그아웃: 실제 저장 세션으로 로그아웃하면 chat의 로그아웃 리스너가 그 회원·세션을 받는지를 웹소켓 없이 리스너 수준에서 본다.</li>
 * </ul>
 */
@JourneyTest
class ModuleEventJourneyIntegrationTest {

    /** 이 테스트에서 발행한 이벤트를 가리는 기준 시각 */
    private Timestamp eventCheckpoint;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JourneyFixtures fixtures;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatUserRepository chatUserRepository;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @Autowired
    private AppearanceUserRepository appearanceUserRepository;

    @Autowired
    private BillingUserRepository billingUserRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @MockitoSpyBean
    private ChatLogoutEventListener chatLogoutEventListener;

    private JourneySessions sessions;

    @BeforeEach
    void setUp() {
        eventCheckpoint = fixtures.eventCheckpoint();
        sessions = new JourneySessions(sessionRepository);
    }

    @AfterEach
    void deleteSessions() {
        fixtures.awaitEventsCompletedSince(eventCheckpoint);
        sessions.deleteAll();
    }

    @Test
    @DisplayName("회원 정지(UserModifiedEvent 상태 변경) → chat 채팅 불가, lecture·appearance·billing 복제본 상태 정지")
    void suspension() {
        Long userId = fixtures.member("journey-suspend", "정지대상", Role.INFLUENCER);
        assertThat(chatUserRepository.findById(userId).orElseThrow().canChat()).isTrue();

        UserDto current = userRepository.findById(userId).orElseThrow().toDto();
        UserDto suspended = new UserDto(userId, current.nickname(), current.role(), UserStatus.SUSPENDED,
                current.version() + 1);
        // 회원 모듈이 상태 변경을 커밋할 때처럼 트랜잭션 안에서 발행한다 (@ApplicationModuleListener는 커밋 뒤 실행)
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserModifiedEvent(suspended)));

        await().atMost(JourneyFixtures.EVENT_TIMEOUT).untilAsserted(() -> {
            assertThat(chatUserRepository.findById(userId)).hasValueSatisfying(chatUser -> {
                assertThat(chatUser.getStatus()).isEqualTo(UserStatus.SUSPENDED);
                assertThat(chatUser.canChat()).isFalse();
            });
            assertThat(lectureUserRepository.findById(userId)).hasValueSatisfying(
                    replica -> assertThat(replica.getStatus()).isEqualTo(UserStatus.SUSPENDED));
            assertThat(appearanceUserRepository.findById(userId)).hasValueSatisfying(
                    replica -> assertThat(replica.getStatus()).isEqualTo(UserStatus.SUSPENDED));
            assertThat(billingUserRepository.findById(userId)).hasValueSatisfying(
                    replica -> assertThat(replica.getStatus()).isEqualTo(UserStatus.SUSPENDED));
        });
    }

    @Test
    @DisplayName("로그아웃하면 chat 로그아웃 리스너가 그 회원 ID와 끝나는 세션 ID를 받는다 (웹소켓 없이)")
    void logoutEventReachesChat() throws Exception {
        Long userId = fixtures.member("journey-logout", "로그아웃대상", Role.INFLUENCER);
        String sessionId = sessions.login(userId, Role.INFLUENCER);

        mockMvc.perform(post("/api/v1/members/logout").cookie(JourneySessions.cookie(sessionId)).with(csrf()))
                .andExpect(status().isOk());

        verify(chatLogoutEventListener, timeout(JourneyFixtures.EVENT_TIMEOUT.toMillis())).on(argThat(event ->
                userId.equals(event.userId()) && sessionId.equals(event.sessionId())));
        assertThat(sessions.exists(sessionId)).isFalse();
    }
}
