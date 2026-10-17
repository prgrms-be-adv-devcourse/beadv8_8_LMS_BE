package com.hapbang.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.NotificationKind;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.journey.support.JourneyFixtures;
import com.hapbang.journey.support.JourneyTest;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

/**
 * 시나리오 3 — 출연 요청·모집 (#326). 실제 가입한 인플루언서끼리 HTTP로 출연을 맺고, 실제 Billing의 보류·지급·반환·위약금과
 * 채팅 알림(실제 Modulith 이벤트)이 이어지는지 본다. 방송 시각은 만들 때 미래만 허용되므로, 확인 단계에서는 출연의
 * 방송 예정 시각만 SQL로 과거로 옮긴다 (AppearanceBillingFlowIntegrationTest와 같은 방식).
 */
@JourneyTest
class AppearanceJourneyIntegrationTest {

    /** 이 테스트에서 발행한 이벤트를 가리는 기준 시각 */
    private Timestamp eventCheckpoint;

    private static final long FUNDED_HP = 100_000;
    private static final long FEE = 30_000;
    private static final long PENALTY = 9_000; // 출연료의 30%

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JourneyFixtures fixtures;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    private Clock clock;

    private Long hostId;
    private Long guestId;

    /** DB 정리 전에 이 테스트의 비동기 이벤트 처리가 끝나기를 기다린다 */
    @AfterEach
    void awaitEvents() {
        fixtures.awaitEventsCompletedSince(eventCheckpoint);
    }

    @BeforeEach
    void setUp() {
        eventCheckpoint = fixtures.eventCheckpoint();
        hostId = fixtures.member("journey-host", "여정호스트", Role.INFLUENCER);
        guestId = fixtures.member("journey-guest", "여정게스트", Role.INFLUENCER);
        fixtures.fund(hostId, FUNDED_HP, "appearance-host");
    }

    @Test
    @DisplayName("출연 요청: 보류 → 게스트에게 채팅 알림 → 수락(성립) → 방송 뒤 호스트 확인 → 게스트 수익 HP, 보류 0, 지급 작업 완료")
    void requestJourney() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        ResultActions created = perform(hostId, "/api/v1/appearances/requests", """
                {"guestId": %d, "title": "금요일 밤 합방", "hostChannelUrl": "https://chzzk.naver.com/host",
                 "scheduledAt": "%s", "expectedMinutes": 120, "content": "게임 합방 출연 부탁드려요", "fee": %d,
                 "acceptDeadline": "%s"}""".formatted(guestId, now.plusDays(7), FEE, now.plusDays(2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
        Long requestId = longOf(created, "$.data.id");
        long version = longOf(created, "$.data.version");

        // 요청 시 출연료가 호스트 충전 HP에서 보류된다
        assertThat(fixtures.heldHp(BillingReferenceType.APPEARANCE_REQUEST, requestId)).isEqualTo(FEE);
        assertThat(fixtures.wallet(hostId).getChargeBalanceHp()).isEqualTo(FUNDED_HP - FEE);
        // 게스트가 호스트와의 1:1 방에서 출연 요청 알림을 받는다
        assertReceived(guestId, hostId, NotificationKind.APPEARANCE_REQUEST_SENT, requestId);

        Long appearanceId = longOf(perform(guestId, "/api/v1/appearances/requests/" + requestId + "/accept",
                """
                        {"version": %d}""".formatted(version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED")), "$.data.appearanceId");

        broadcastEnded(appearanceId);
        perform(hostId, "/api/v1/appearances/" + appearanceId + "/confirm", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmType").value("HOST"))
                .andExpect(jsonPath("$.data.hpPayoutStatus").value("DONE"));

        assertThat(fixtures.wallet(guestId).getRevenueBalanceHp()).isEqualTo(FEE);
        assertThat(fixtures.wallet(hostId).getChargeBalanceHp()).isEqualTo(FUNDED_HP - FEE);
        assertThat(fixtures.heldHp(BillingReferenceType.APPEARANCE_REQUEST, requestId)).isZero();
        assertThat(hpTasks()).containsExactly("PAYOUT DONE");
    }

    @Test
    @DisplayName("출연 모집: 3자리 보류 → 지원 시 호스트에게 채팅 알림 → 1명 선택 → 조기 마감 → 빈 2자리 반환")
    void recruitJourney() throws Exception {
        Long recruitId = createRecruit(3);
        assertThat(fixtures.heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isEqualTo(FEE * 3);

        Long applicationId = apply(recruitId);
        ChatMessage submitted = assertReceived(hostId, guestId, NotificationKind.APPEARANCE_APPLICATION_SUBMITTED,
                applicationId);
        assertThat(submitted.getParentReferenceId()).isEqualTo(recruitId);

        perform(hostId, "/api/v1/appearances/applications/" + applicationId + "/accept", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SELECTED"));
        perform(hostId, "/api/v1/appearances/recruits/" + recruitId + "/close", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closeType").value("EARLY"))
                .andExpect(jsonPath("$.data.hpReleaseStatus").value("DONE"));

        // 빈 2자리 출연료가 호스트에게 돌아오고, 선택된 1자리만 보류에 남는다
        assertThat(fixtures.wallet(hostId).getChargeBalanceHp()).isEqualTo(FUNDED_HP - FEE);
        assertThat(fixtures.heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isEqualTo(FEE);
        assertThat(hpTasks()).containsExactly("RELEASE DONE");
    }

    @Test
    @DisplayName("출연 모집 전체 취소: 선택된 게스트에게 위약금 30%, 나머지 출연료와 빈자리는 호스트에게 반환")
    void recruitCancel() throws Exception {
        Long recruitId = createRecruit(2);
        Long applicationId = apply(recruitId);
        perform(hostId, "/api/v1/appearances/applications/" + applicationId + "/accept", null)
                .andExpect(status().isOk());

        perform(hostId, "/api/v1/appearances/recruits/" + recruitId + "/cancel", """
                {"reason": "방송 일정이 취소됐어요"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        assertThat(fixtures.wallet(guestId).getRevenueBalanceHp()).isEqualTo(PENALTY);
        assertThat(fixtures.wallet(hostId).getChargeBalanceHp()).isEqualTo(FUNDED_HP - PENALTY);
        assertThat(fixtures.heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isZero();
        assertThat(hpTasks()).containsExactlyInAnyOrder("PENALTY DONE", "RELEASE DONE");
    }

    private Long createRecruit(int recruitCount) throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        return longOf(perform(hostId, "/api/v1/appearances/recruits", """
                {"title": "주말 게임 합방 모집", "hostChannelUrl": "https://chzzk.naver.com/host",
                 "scheduledAt": "%s", "expectedMinutes": 120, "content": "같이 게임해요", "feePerPerson": %d,
                 "recruitCount": %d, "recruitDeadline": "%s"}"""
                .formatted(now.plusDays(7), FEE, recruitCount, now.plusDays(2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("RECRUITING")), "$.data.id");
    }

    private Long apply(Long recruitId) throws Exception {
        return longOf(perform(guestId, "/api/v1/appearances/recruits/" + recruitId + "/applications", """
                {"message": "지원합니다"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("APPLIED")), "$.data.id");
    }

    private ResultActions perform(Long userId, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).with(TestLogin.as(userId, Role.INFLUENCER)).with(csrf());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private static long longOf(ResultActions result, String path) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), path)).longValue();
    }

    /** 방송 예정 시각이 지난 것으로 만든다 (만들 때는 미래만 허용) */
    private void broadcastEnded(Long appearanceId) {
        jdbcTemplate.update("UPDATE appearances.appearances SET scheduled_at = ? WHERE id = ?",
                LocalDateTime.now(clock).minusHours(1), appearanceId);
    }

    private List<String> hpTasks() {
        return jdbcTemplate.queryForList("SELECT task_type || ' ' || status FROM appearances.hp_tasks ORDER BY id",
                String.class);
    }

    /**
     * 알림이 보낸 사람·받는 사람 두 명이 참여한 1:1 방에 남고, 받는 사람이 자기 권한으로 메시지 이력을 조회해 볼 수 있는지 확인한다.
     */
    private ChatMessage assertReceived(Long receiverId, Long senderId, NotificationKind kind, Long referenceId)
            throws Exception {
        await().atMost(JourneyFixtures.EVENT_TIMEOUT).until(() -> notification(kind, referenceId).isPresent());
        ChatMessage message = notification(kind, referenceId).orElseThrow();
        assertThat(message.getSenderUserId()).isEqualTo(senderId);
        Long chatRoomId = message.getChatRoom().getId();
        assertThat(jdbcTemplate.queryForList("""
                SELECT user_id FROM chats.chat_room_member
                WHERE chat_room_id = ? AND left_at IS NULL AND deleted_at IS NULL""", Long.class, chatRoomId))
                .containsExactlyInAnyOrder(senderId, receiverId);
        mockMvc.perform(get("/api/v1/chats/{id}/messages", chatRoomId).with(TestLogin.as(receiverId, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].messageType").value("NOTIFICATION"))
                .andExpect(jsonPath("$.data.messages[0].notificationKind").value(kind.name()))
                .andExpect(jsonPath("$.data.messages[0].referenceId").value(referenceId));
        return message;
    }

    private Optional<ChatMessage> notification(NotificationKind kind, Long referenceId) {
        return chatMessageRepository.findAll().stream()
                .filter(message -> message.getNotificationKind() == kind && referenceId.equals(message.getReferenceId()))
                .findFirst();
    }
}
