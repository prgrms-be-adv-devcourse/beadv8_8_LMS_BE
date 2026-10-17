package com.hapbang.journey;

import static com.hapbang.journey.support.JourneyStomp.TIMEOUT_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.socket.CloseStatus;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.NotificationKind;
import com.hapbang.chat.in.dto.ChatErrorMessage;
import com.hapbang.chat.in.dto.ChatNotification;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.journey.support.JourneyFixtures;
import com.hapbang.journey.support.JourneySessions;
import com.hapbang.journey.support.JourneyStomp;
import com.hapbang.journey.support.JourneyWebTest;
import com.hapbang.shared.member.domain.Role;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * 시나리오 5 — 채팅이 실제로 동작하는지 (#326). chat 모듈의 STOMP 테스트는 다른 모듈을 대역으로 두지만, 여기서는 실제로 가입한
 * 회원·실제 JDBC 세션·실제 출연 모듈·실제 회원 이벤트에서 출발해 STOMP 웹소켓 송수신과 연결 종료까지 이어지는지 본다.
 * REST는 같은 세션 쿠키로 MockMvc에서, 웹소켓은 실제 포트로 연결한다.
 */
@JourneyWebTest
class ChatJourneyIntegrationTest {

    private static final long QUIET_MILLIS = 500;

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JourneyFixtures fixtures;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    private Clock appearanceClock;

    private Timestamp eventCheckpoint;
    private JourneySessions sessions;
    private JourneyStomp stomp;

    private Long hostId;
    private Long guestId;
    private String hostSession;
    private String guestSession;

    @BeforeEach
    void setUp() {
        eventCheckpoint = fixtures.eventCheckpoint();
        sessions = new JourneySessions(sessionRepository);
        stomp = new JourneyStomp(port, applicationContext);
        hostId = fixtures.member("chat-host", "채팅호스트", Role.INFLUENCER);
        guestId = fixtures.member("chat-guest", "채팅게스트", Role.INFLUENCER);
        hostSession = sessions.login(hostId, Role.INFLUENCER);
        guestSession = sessions.login(guestId, Role.INFLUENCER);
    }

    /** 연결 정리 → 이벤트 처리 대기 → 로그인 세션 삭제. 앞 단계가 실패해도 뒤 단계는 실행한다 */
    @AfterEach
    void tearDown() {
        try {
            stomp.closeAll();
        } finally {
            try {
                fixtures.awaitEventsCompletedSince(eventCheckpoint);
            } finally {
                sessions.deleteAll();
            }
        }
    }

    @Test
    @DisplayName("채팅 시작 → 실시간 송수신(둘 다 메시지, 상대만 알림) → DB 저장 → 상대 메시지 안 읽은 수 1 → 읽음 처리 0")
    void chatAndRead() throws Exception {
        Long chatRoomId = createRoom(hostSession, guestId, 201);
        StompSession host = stomp.connect(hostSession);
        StompSession guest = stomp.connect(guestSession);
        BlockingQueue<ChatMessageResponse> hostMessages = messages(host);
        BlockingQueue<ChatMessageResponse> guestMessages = messages(guest);
        BlockingQueue<ChatNotification> hostNotifications = notifications(host);
        BlockingQueue<ChatNotification> guestNotifications = notifications(guest);
        stomp.awaitSubscriptions(4, hostId, guestId);

        send(host, chatRoomId, "다음 주 합방 어떠세요?", "host-msg-1");

        ChatMessageResponse received = poll(guestMessages);
        assertThat(received.content()).isEqualTo("다음 주 합방 어떠세요?");
        assertThat(received.senderUserId()).isEqualTo(hostId);
        assertThat(received.roomSequence()).isEqualTo(1);
        assertThat(received.clientMessageId()).isNull();
        ChatMessageResponse echoed = poll(hostMessages);
        assertThat(echoed.messageId()).isEqualTo(received.messageId());
        assertThat(echoed.clientMessageId()).isEqualTo("host-msg-1");
        assertThat(poll(guestNotifications).messageId()).isEqualTo(received.messageId());
        // 보낸 사람은 자기 메시지 알림을 받지 않는다: 상대의 답장이 보낸 사람의 첫 알림이다(회원별 전달 순서 보장)
        send(guest, chatRoomId, "네 좋아요", "guest-msg-1");
        ChatMessageResponse reply = poll(hostMessages);
        assertThat(poll(hostNotifications).messageId()).isEqualTo(reply.messageId());
        assertThat(chatMessageRepository.findById(received.messageId())).hasValueSatisfying(message -> {
            assertThat(message.getRoomSequence()).isEqualTo(1);
            assertThat(message.getContent()).isEqualTo("다음 주 합방 어떠세요?");
        });

        mockMvc.perform(get("/api/v1/chats").cookie(cookie(guestSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].chatRoomId").value(chatRoomId))
                .andExpect(jsonPath("$.data[0].unreadCount").value(1))
                .andExpect(jsonPath("$.data[0].lastMessage.content").value("네 좋아요"));
        mockMvc.perform(put("/api/v1/chats/{id}/read", chatRoomId).cookie(cookie(guestSession)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"lastReadSequence": 1}"""))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/chats").cookie(cookie(guestSession)))
                .andExpect(jsonPath("$.data[0].unreadCount").value(0));
    }

    @Test
    @DisplayName("같은 clientMessageId로 다시 보내면 보낸 사람에게만 기존 메시지가 가고 상대는 아무것도 받지 않는다 (#261)")
    void resendIsIdempotent() throws Exception {
        Long chatRoomId = createRoom(hostSession, guestId, 201);
        StompSession host = stomp.connect(hostSession);
        StompSession guest = stomp.connect(guestSession);
        BlockingQueue<ChatMessageResponse> hostMessages = messages(host);
        BlockingQueue<ChatMessageResponse> guestMessages = messages(guest);
        BlockingQueue<ChatNotification> guestNotifications = notifications(guest);
        stomp.awaitSubscriptions(3, hostId, guestId);

        send(host, chatRoomId, "안녕하세요", "resend-1");
        ChatMessageResponse first = poll(hostMessages);
        poll(guestMessages);
        poll(guestNotifications);

        send(host, chatRoomId, "안녕하세요", "resend-1");
        ChatMessageResponse resent = poll(hostMessages);

        assertThat(resent.messageId()).isEqualTo(first.messageId());
        assertThat(resent.clientMessageId()).isEqualTo("resend-1");
        // 회원별 전달 순서는 지켜지므로, 재전송이 상대에게 갔다면 다음 메시지보다 먼저 도착한다.
        // 다음 메시지가 상대의 첫 수신(메시지·알림)이면 재전송은 상대에게 가지 않은 것이다
        send(host, chatRoomId, "다음 메시지", "next-1");
        ChatMessageResponse next = poll(hostMessages);
        assertThat(poll(guestMessages).messageId()).isEqualTo(next.messageId());
        assertThat(poll(guestNotifications).messageId()).isEqualTo(next.messageId());
        assertThat(chatMessageRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("출연 요청·모집 지원 → 실제 이벤트 → 받는 사람의 STOMP 구독에 NOTIFICATION이 실시간으로 도착한다 (모듈 경계)")
    void appearanceNotificationsArriveOverStomp() throws Exception {
        fixtures.fund(hostId, 100_000, "chat-host");
        StompSession host = stomp.connect(hostSession);
        StompSession guest = stomp.connect(guestSession);
        BlockingQueue<ChatMessageResponse> hostMessages = messages(host);
        BlockingQueue<ChatMessageResponse> guestMessages = messages(guest);
        stomp.awaitSubscriptions(2, hostId, guestId);
        LocalDateTime now = LocalDateTime.now(appearanceClock).withNano(0);

        // 출연 요청(실제 Billing 보류) → 게스트에게 실시간 알림
        Long requestId = idOf(mockMvc.perform(post("/api/v1/appearances/requests").cookie(cookie(hostSession))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"guestId": %d, "title": "금요일 밤 합방", "hostChannelUrl": "https://chzzk.naver.com/host",
                                 "scheduledAt": "%s", "expectedMinutes": 120, "content": "게임 합방 출연 부탁드려요",
                                 "fee": 30000, "acceptDeadline": "%s"}""".formatted(guestId, now.plusDays(7),
                                now.plusDays(2))))
                .andExpect(status().isCreated()));
        ChatMessageResponse requestNotice = pollUntilNotification(guestMessages, NotificationKind.APPEARANCE_REQUEST_SENT);
        assertThat(requestNotice.notificationKind()).isEqualTo(NotificationKind.APPEARANCE_REQUEST_SENT);
        assertThat(requestNotice.referenceId()).isEqualTo(requestId);
        assertThat(requestNotice.senderUserId()).isEqualTo(hostId);

        // 모집 지원 → 호스트에게 실시간 알림 (모집글 ID 포함)
        Long recruitId = idOf(mockMvc.perform(post("/api/v1/appearances/recruits").cookie(cookie(hostSession))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"title": "주말 게임 합방 모집", "hostChannelUrl": "https://chzzk.naver.com/host",
                                 "scheduledAt": "%s", "expectedMinutes": 120, "content": "같이 게임해요",
                                 "feePerPerson": 30000, "recruitCount": 1, "recruitDeadline": "%s"}"""
                                .formatted(now.plusDays(7), now.plusDays(2))))
                .andExpect(status().isCreated()));
        Long applicationId = idOf(mockMvc.perform(post("/api/v1/appearances/recruits/{id}/applications", recruitId)
                        .cookie(cookie(guestSession)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"message": "지원합니다"}"""))
                .andExpect(status().isCreated()));
        ChatMessageResponse applicationNotice = pollUntilNotification(hostMessages, NotificationKind.APPEARANCE_APPLICATION_SUBMITTED);
        assertThat(applicationNotice.notificationKind()).isEqualTo(NotificationKind.APPEARANCE_APPLICATION_SUBMITTED);
        assertThat(applicationNotice.referenceId()).isEqualTo(applicationId);
        assertThat(applicationNotice.parentReferenceId()).isEqualTo(recruitId);
        assertThat(applicationNotice.senderUserId()).isEqualTo(guestId);
    }

    @Test
    @DisplayName("기기 2대 중 하나로 로그아웃하면 그 세션의 소켓만 1008 LOGGED_OUT으로 닫히고, 다른 기기는 유지, 같은 쿠키로 재연결 거절 (#242)")
    void logoutClosesOnlyThatSession() throws Exception {
        String secondDevice = sessions.login(hostId, Role.INFLUENCER);
        StompSession pc = stomp.connect(hostSession);
        StompSession phone = stomp.connect(secondDevice);
        BlockingQueue<CloseStatus> disconnects = stomp.disconnectsOf(hostId);

        mockMvc.perform(post("/api/v1/members/logout").cookie(cookie(hostSession)).with(csrf()))
                .andExpect(status().isOk());

        CloseStatus closed = disconnects.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(closed).isNotNull();
        assertThat(closed.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(closed.getReason()).isEqualTo("LOGGED_OUT");
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !pc.isConnected());
        assertThat(disconnects.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS)).isNull();
        assertThat(phone.isConnected()).isTrue();
        assertThatThrownBy(() -> stomp.connect(hostSession)).isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("일반 유저로 강등(실제 UserModifiedEvent)되면 모든 연결이 1008 CHAT_NOT_ALLOWED로 닫히고 이후 채팅 API는 403")
    void demotionClosesAllConnections() throws Exception {
        String secondDevice = sessions.login(hostId, Role.INFLUENCER);
        StompSession pc = stomp.connect(hostSession);
        StompSession phone = stomp.connect(secondDevice);
        BlockingQueue<CloseStatus> disconnects = stomp.disconnectsOf(hostId);

        fixtures.changeRole(hostId, Role.USER);

        for (int i = 0; i < 2; i++) {
            CloseStatus closed = disconnects.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(closed).isNotNull();
            assertThat(closed.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
            assertThat(closed.getReason()).isEqualTo("CHAT_NOT_ALLOWED");
        }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !pc.isConnected() && !phone.isConnected());
        // 회원 모듈이 저장된 세션 권한도 USER로 바꿔, 같은 쿠키로는 채팅 API를 쓸 수 없다
        assertThat(sessions.roleOf(hostSession)).isEqualTo(Role.USER);
        mockMvc.perform(get("/api/v1/chats").cookie(cookie(hostSession)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("상대가 나가면 퇴장 안내가 실시간으로 오고 보내기는 CHAT_PARTNER_LEFT, 상대가 다시 시작하면 재입장 안내 뒤 송수신 정상")
    void leaveAndRejoin() throws Exception {
        Long chatRoomId = createRoom(hostSession, guestId, 201);
        StompSession host = stomp.connect(hostSession);
        StompSession guest = stomp.connect(guestSession);
        BlockingQueue<ChatMessageResponse> hostMessages = messages(host);
        BlockingQueue<ChatMessageResponse> guestMessages = messages(guest);
        BlockingQueue<ChatErrorMessage> hostErrors = stomp.subscribe(host, "/user/queue/chats/errors",
                ChatErrorMessage.class);
        stomp.awaitSubscriptions(3, hostId, guestId);

        mockMvc.perform(delete("/api/v1/chats/{id}/members/me", chatRoomId).cookie(cookie(guestSession)).with(csrf()))
                .andExpect(status().isNoContent());
        ChatMessageResponse leftNotice = poll(hostMessages);
        assertThat(leftNotice.messageType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(leftNotice.content()).isEqualTo("채팅게스트님이 나갔습니다.");

        send(host, chatRoomId, "아직 계세요?", "after-leave");
        ChatErrorMessage error = hostErrors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("CHAT_PARTNER_LEFT");
        assertThat(error.clientMessageId()).isEqualTo("after-leave");

        assertThat(createRoom(guestSession, hostId, 200)).isEqualTo(chatRoomId);
        ChatMessageResponse rejoinNotice = poll(hostMessages);
        assertThat(rejoinNotice.messageType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(rejoinNotice.content()).isEqualTo("채팅게스트님이 다시 들어왔습니다.");

        send(host, chatRoomId, "다시 왔네요", "after-rejoin");
        assertThat(poll(guestMessages).content()).isEqualTo("다시 왔네요");
    }

    private Long createRoom(String sessionId, Long targetUserId, int expectedStatus) throws Exception {
        return idOf(mockMvc.perform(post("/api/v1/chats").cookie(cookie(sessionId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"targetUserId": %d}""".formatted(targetUserId)))
                .andExpect(status().is(expectedStatus)), "$.data.chatRoomId");
    }

    private BlockingQueue<ChatMessageResponse> messages(StompSession session) {
        return stomp.subscribe(session, "/user/queue/chats/messages", ChatMessageResponse.class);
    }

    private BlockingQueue<ChatNotification> notifications(StompSession session) {
        return stomp.subscribe(session, "/user/queue/chats/notifications", ChatNotification.class);
    }

    private static void send(StompSession session, Long chatRoomId, String content, String clientMessageId) {
        session.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest(content, clientMessageId));
    }

    private static <T> T poll(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(value).as("STOMP 수신").isNotNull();
        return value;
    }

    /**
     * 이 종류의 알림이 올 때까지 받는다. 알림은 방의 두 참여자 모두에게 전달되므로(보낸 사람 화면에도 남는다) 앞서 온 다른 알림은 건너뛴다
     */
    private static ChatMessageResponse pollUntilNotification(BlockingQueue<ChatMessageResponse> queue,
            NotificationKind kind) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS * 2);
        while (System.currentTimeMillis() < deadline) {
            ChatMessageResponse message = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (message != null && message.messageType() == ChatMessageType.NOTIFICATION
                    && message.notificationKind() == kind) {
                return message;
            }
        }
        throw new AssertionError(kind + " 알림을 받지 못했습니다");
    }

    private static Cookie cookie(String sessionId) {
        return JourneySessions.cookie(sessionId);
    }

    private static Long idOf(ResultActions result) throws Exception {
        return idOf(result, "$.data.id");
    }

    private static Long idOf(ResultActions result, String path) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), path)).longValue();
    }
}
