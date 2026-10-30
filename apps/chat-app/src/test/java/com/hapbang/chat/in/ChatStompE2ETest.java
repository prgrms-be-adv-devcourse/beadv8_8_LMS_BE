package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.dto.ChatErrorMessage;
import com.hapbang.chat.in.dto.ChatNotification;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserLoggedOutEvent;

/**
 * 실제 서버를 띄워 STOMP(WebSocketStompClient)로 채팅 흐름을 검증한다.
 * 웹소켓 핸드셰이크와 REST 호출(RestTestClient) 모두 로그인 세션 쿠키로 보낸다({@link E2ESession}).
 */
@ChatE2ETest
class ChatStompE2ETest {

    private static final long TIMEOUT_SECONDS = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    private RestTestClient client;
    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    @Test
    void 로그인하지_않으면_웹소켓에_연결할_수_없다() {
        assertThatThrownBy(() -> stompClient.connectAsync(wsUrl(), new WebSocketHttpHeaders(), new StompHeaders(),
                new StompSessionHandlerAdapter() {
                }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void STOMP로_보낸_메시지가_방을_연_상대에게_방_topic으로_보낸_사람에게_결과_확인으로_가고_방을_열지_않은_상대_연결만_알림을_받으며_DB에_저장된다()
            throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = connect(HOST_ID);
        StompSession guest = connect(GUEST_ID);
        StompSession guestElsewhere = connect(GUEST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatMessageResponse> guestMessages = subscribeRoom(guest, chatRoomId, 1);
        BlockingQueue<ChatNotification> guestNotifications = subscribe(guestElsewhere,
                "/user/queue/chats/notifications", ChatNotification.class);
        BlockingQueue<ChatNotification> hostNotifications = subscribe(host,
                "/user/queue/chats/notifications", ChatNotification.class);
        waitForSubscriptions();

        host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("금요일 밤 9시 괜찮으세요?", "client-1"));

        ChatMessageResponse received = guestMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.content()).isEqualTo("금요일 밤 9시 괜찮으세요?");
        assertThat(received.senderUserId()).isEqualTo(HOST_ID);
        assertThat(received.roomSequence()).isEqualTo(1);
        // 전송 ID는 보낸 사람에게만 담긴다 (#261)
        assertThat(received.clientMessageId()).isNull();
        assertThat(hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .extracting(ChatMessageResponse::messageId, ChatMessageResponse::clientMessageId)
                .containsExactly(received.messageId(), "client-1");

        ChatNotification notification = guestNotifications.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(notification).isNotNull();
        assertThat(notification.type()).isEqualTo(ChatNotification.NotificationType.ROOM_ACTIVITY);
        assertThat(notification.lastMessage().messageId()).isEqualTo(received.messageId());
        assertThat(notification.messageCount()).isEqualTo(1);
        assertThat(hostNotifications.poll(500, TimeUnit.MILLISECONDS)).isNull();

        assertThat(messageContents(chatRoomId, GUEST_ID)).containsExactly("금요일 밤 9시 괜찮으세요?");
    }

    @Test
    void 같은_전송_ID로_다시_보내면_보낸_사람에게만_기존_메시지가_다시_가고_상대는_한_번만_받는다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = connect(HOST_ID);
        StompSession guest = connect(GUEST_ID);
        StompSession guestElsewhere = connect(GUEST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatMessageResponse> guestMessages = subscribeRoom(guest, chatRoomId, 1);
        BlockingQueue<ChatNotification> guestNotifications = subscribe(guestElsewhere,
                "/user/queue/chats/notifications", ChatNotification.class);
        waitForSubscriptions();

        host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("안녕하세요", "client-1"));
        ChatMessageResponse first = hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("안녕하세요", "client-1"));
        ChatMessageResponse resent = hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(first).isNotNull();
        assertThat(resent).isNotNull();
        assertThat(resent.messageId()).isEqualTo(first.messageId());
        assertThat(resent.clientMessageId()).isEqualTo("client-1");
        assertThat(guestMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        assertThat(guestMessages.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(guestNotifications.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        assertThat(guestNotifications.poll(1500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(messageContents(chatRoomId, GUEST_ID)).containsExactly("안녕하세요");
    }

    @Test
    void 상대가_방을_나가면_남은_사람에게만_퇴장_안내가_실시간으로_가고_남은_사람은_보낼_수_없다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = connect(HOST_ID);
        StompSession guest = connect(GUEST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribeRoom(host, chatRoomId, 1);
        BlockingQueue<ChatMessageResponse> guestMessages = subscribeRoom(guest, chatRoomId, 1);
        BlockingQueue<ChatErrorMessage> hostErrors = subscribe(host, "/user/queue/chats/errors",
                ChatErrorMessage.class);
        waitForSubscriptions();

        client.delete().uri("/api/v1/chats/{id}/members/me", chatRoomId)
                .headers(E2ESession.login(client, GUEST_ID, Role.VERIFIED_INFLUENCER)::applyTo)
                .exchange()
                .expectStatus().isNoContent();

        ChatMessageResponse leftNotice = hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(leftNotice).isNotNull();
        assertThat(leftNotice.messageType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(leftNotice.content()).isEqualTo("게스트님이 나갔습니다.");
        assertThat(guestMessages.poll(500, TimeUnit.MILLISECONDS)).isNull();

        host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("아직 계세요?", "client-2"));

        ChatErrorMessage error = hostErrors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("CHAT_PARTNER_LEFT");
        assertThat(error.clientMessageId()).isEqualTo("client-2");
        assertThat(guestMessages.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void 나간_상대가_다시_채팅을_시작하면_남은_사람에게만_재입장_안내가_실시간으로_간다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        client.delete().uri("/api/v1/chats/{id}/members/me", chatRoomId)
                .headers(E2ESession.login(client, GUEST_ID, Role.VERIFIED_INFLUENCER)::applyTo)
                .exchange()
                .expectStatus().isNoContent();
        // 퇴장 안내는 outbox 경로로 비동기 전달된다. 발행(PUBLISHED)은 로컬 전달 완료가 아니므로(decisions.md), 연결 뒤에
        // 늦은 퇴장 안내가 먼저 도착할 수 있다. 받은 순서에 기대지 않고 내용으로 재입장 안내를 찾는다
        StompSession host = connect(HOST_ID);
        StompSession guest = connect(GUEST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribeRoom(host, chatRoomId, 1);
        BlockingQueue<ChatMessageResponse> guestMessages = subscribe(guest, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        waitForSubscriptions();

        client.post().uri("/api/v1/chats")
                .headers(E2ESession.login(client, GUEST_ID, Role.VERIFIED_INFLUENCER)::applyTo)
                .body(new CreateChatRoomRequest(HOST_ID))
                .exchange()
                .expectStatus().isOk();

        List<ChatMessageResponse> beforeRejoinNotice = new ArrayList<>();
        ChatMessageResponse rejoinNotice = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (rejoinNotice == null && System.nanoTime() < deadline) {
            ChatMessageResponse received = hostMessages.poll(100, TimeUnit.MILLISECONDS);
            if (received == null) {
                continue;
            }
            if ("게스트님이 다시 들어왔습니다.".equals(received.content())) {
                rejoinNotice = received;
            } else {
                beforeRejoinNotice.add(received);
            }
        }
        assertThat(rejoinNotice).isNotNull();
        assertThat(rejoinNotice.messageType()).isEqualTo(ChatMessageType.SYSTEM);
        // 재입장 안내 말고 받을 수 있는 것은 늦게 도착한 퇴장 안내뿐이다
        assertThat(beforeRejoinNotice).extracting(ChatMessageResponse::content)
                .allMatch("게스트님이 나갔습니다."::equals);
        // 재입장한 게스트는 퇴장 안내도, 재입장 안내도 받지 않는다
        assertThat(guestMessages.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(messageContents(chatRoomId, HOST_ID)).first().isEqualTo("게스트님이 다시 들어왔습니다.");
    }

    // 화면 닫기(연결 끊김)는 퇴장이 아니다.
    @Test
    void 연결이_끊겨도_퇴장으로_처리하지_않는다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession guest = connect(GUEST_ID);
        guest.disconnect();
        StompSession host = connect(HOST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribeRoom(host, chatRoomId, 1);
        waitForSubscriptions();

        host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("다시 오면 보세요", null));

        assertThat(hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        assertThat(messageContents(chatRoomId, GUEST_ID)).containsExactly("다시 오면 보세요");
    }

    @Test
    void STOMP로_빈_메시지를_보내면_보낸_사람에게_오류가_간다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = connect(HOST_ID);
        BlockingQueue<ChatErrorMessage> errors = subscribe(host, "/user/queue/chats/errors", ChatErrorMessage.class);
        waitForSubscriptions();

        host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest(" ", "client-3"));

        ChatErrorMessage error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("INVALID_REQUEST");
        assertThat(error.message()).isEqualTo("메시지 내용이 비어 있습니다.");
        assertThat(error.clientMessageId()).isEqualTo("client-3");
    }

    /**
     * 거절된 구독은 서버가 PROTOCOL_ERROR로 연결을 닫는 것으로 확인한다.
     * Spring은 ERROR 프레임을 보낸 직후 연결을 닫아서, 다른 전송과 겹치면 ERROR 프레임이 클라이언트에 도착하지 않을 수 있다(#173).
     * 그래서 ERROR 프레임은 도착했을 때만 문구를 확인한다.
     */
    @Test
    void 다른_회원_큐로_직접_보내면_연결이_끊기고_상대에게_전달되지_않는다() throws Exception {
        StompSession host = connect(HOST_ID);
        BlockingQueue<byte[]> hostMessages = subscribe(host, "/user/queue/chats/messages", byte[].class);
        StompEvents guestEvents = new StompEvents();
        StompSession guest = connect(GUEST_ID, Role.INFLUENCER, guestEvents);
        waitForSubscriptions();

        CloseStatus closeStatus = closeStatusOf(GUEST_ID, () -> guest.send("/user/" + HOST_ID
                + "/queue/chats/messages", "{\"content\": \"위조\"}".getBytes()));

        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
        assertThat(guestEvents.errorFrames).allSatisfy(error -> assertThat(error).contains("보낼 수 없는 경로"));
        assertThat(hostMessages.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void 브로커로_직접_보내면_연결이_끊긴다() throws Exception {
        StompSession guest = connect(GUEST_ID);

        CloseStatus closeStatus = closeStatusOf(GUEST_ID,
                () -> guest.send("/queue/chats/messages", "{\"content\": \"위조\"}".getBytes()));

        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
    }

    @Test
    void 채팅_역할이_아닌_회원은_연결할_수_없다() throws Exception {
        StompEvents events = new StompEvents();

        CloseStatus closeStatus = closeStatusOf(USER_ID, () -> {
            try {
                connect(USER_ID, Role.USER, events);
            } catch (ExecutionException | TimeoutException rejected) {
                // 연결 거절은 연결 종료로 확인한다
            }
        });

        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
        assertThat(events.errorFrames).allSatisfy(error -> assertThat(error).contains("인플루언서 이상 또는 관리자만"));
    }

    @Test
    void STOMP_프레임으로_연결해도_채팅_역할이_아니면_거절된다() throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.COOKIE, E2ESession.login(client, USER_ID, Role.USER).cookieHeader());
        BlockingQueue<String> serverFrames = new LinkedBlockingQueue<>();

        CloseStatus closeStatus = closeStatusOf(USER_ID, () -> new StandardWebSocketClient().execute(
                new TextWebSocketHandler() {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        // WebSocketStompClient는 CONNECT만 보내므로 STOMP 1.2의 STOMP 프레임을 직접 보낸다
                        session.sendMessage(new TextMessage("STOMP\naccept-version:1.2\nhost:localhost\n\n\u0000"));
                    }

                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        serverFrames.add(message.getPayload());
                    }
                }, handshakeHeaders, URI.create(wsUrl())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
        assertThat(serverFrames).noneMatch(frame -> frame.startsWith("CONNECTED"));
    }

    @Test
    void 허용하지_않은_출처에서는_웹소켓_핸드셰이크가_403이다() {
        assertThatThrownBy(() -> connectFrom(HOST_ID, "https://evil.example.com"))
                .isInstanceOf(ExecutionException.class)
                // Tomcat 웹소켓 클라이언트는 업그레이드 응답 상태를 "[403]"으로 메시지에 담는다
                .rootCause().hasMessageContaining("[403]");
    }

    @Test
    void 허용한_출처에서는_웹소켓에_연결할_수_있다() throws Exception {
        assertThat(connectFrom(HOST_ID, "http://localhost:5173").isConnected()).isTrue();
    }

    @Test
    void 로그아웃_이벤트를_받으면_그_세션의_연결만_닫히고_다른_기기_연결은_유지된다() throws Exception {
        E2ESession loggingOut = E2ESession.login(client, HOST_ID, Role.INFLUENCER);
        StompSession closing = connectWith(loggingOut, new StompEvents());
        StompSession otherDevice = connect(HOST_ID);

        // 회원 로그아웃 HTTP 처리와 서비스 간 이벤트 전달은 회원 앱·연동 테스트의 책임이다.
        // 이 테스트는 채팅 앱이 이벤트를 받은 뒤 실제 WebSocket 연결을 선택적으로 닫는지 검증한다.
        CloseStatus closeStatus = closeStatusOf(HOST_ID,
                () -> applicationContext.publishEvent(new UserLoggedOutEvent(HOST_ID, loggingOut.httpSessionId())));

        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(closeStatus.getReason()).isEqualTo("LOGGED_OUT");
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !closing.isConnected());
        assertThat(otherDevice.isConnected()).isTrue();
    }

    // 강등·정지된 회원의 연결 종료와 프로필 수정 시 연결 유지는 회원 변경 Kafka 소비가 남긴 연결 재검사 작업이
    // 제어 Stream·제어 채널을 거쳐 처리한다. Kafka가 필요한 그 두 테스트는 ChatMemberControlE2ETest에 있다.

    @Test
    void 정지된_회원은_세션_역할이_인플루언서여도_연결할_수_없다() throws Exception {
        chatUserUseCase.update(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.SUSPENDED));
        StompEvents events = new StompEvents();

        CloseStatus closeStatus = closeStatusOf(HOST_ID, () -> {
            try {
                connect(HOST_ID, Role.INFLUENCER, events);
            } catch (ExecutionException | TimeoutException rejected) {
                // 연결 거절은 연결 종료로 확인한다
            }
        });

        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
    }

    @Test
    void STOMP로_2000자를_넘는_메시지를_보내면_전송_ID와_함께_오류가_간다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = connect(HOST_ID);
        BlockingQueue<ChatErrorMessage> errors = subscribe(host, "/user/queue/chats/errors", ChatErrorMessage.class);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        waitForSubscriptions();

        host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest(
                "가".repeat(ChatMessage.MAX_CONTENT_LENGTH + 1), "too-long-1"));

        ChatErrorMessage error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("INVALID_REQUEST");
        assertThat(error.message()).isEqualTo("메시지는 2000자 이하여야 합니다.");
        assertThat(error.clientMessageId()).isEqualTo("too-long-1");

        host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest(
                "가".repeat(ChatMessage.MAX_CONTENT_LENGTH), "max-1"));
        assertThat(hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .extracting(ChatMessageResponse::clientMessageId).isEqualTo("max-1");
    }

    @Test
    void 회원_큐가_아닌_경로는_구독할_수_없다() throws Exception {
        BlockingQueue<CloseStatus> outsiderDisconnects = new LinkedBlockingQueue<>();
        ApplicationListener<SessionDisconnectEvent> disconnectListener = event -> {
            if (event.getUser() != null && String.valueOf(OTHER_INFLUENCER_ID).equals(event.getUser().getName())) {
                outsiderDisconnects.add(event.getCloseStatus());
            }
        };
        applicationContext.addApplicationListener(disconnectListener);
        try {
            StompEvents events = new StompEvents();
            StompSession outsider = connect(OTHER_INFLUENCER_ID, events);
            assertThat(outsider.isConnected()).isTrue();

            outsider.subscribe("/queue/chats/messages", new StompSessionHandlerAdapter() {
            });

            CloseStatus closeStatus = outsiderDisconnects.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(closeStatus).isNotNull();
            assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
            assertThat(events.transportErrors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ConnectionLostException.class);
            assertThat(outsider.isConnected()).isFalse();
            assertThat(events.errorFrames).allSatisfy(error -> assertThat(error).contains("구독할 수 없는 경로"));
        } finally {
            applicationContext.removeApplicationListener(disconnectListener);
        }
    }

    private Long createRoom(Long userId, Long targetUserId) {
        RsData<ChatRoomResponse> room = client.post().uri("/api/v1/chats")
                .headers(E2ESession.login(client, userId, Role.INFLUENCER)::applyTo)
                .body(new CreateChatRoomRequest(targetUserId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<RsData<ChatRoomResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(room).isNotNull();
        return room.data().chatRoomId();
    }

    private List<String> messageContents(Long chatRoomId, Long userId) {
        RsData<ChatMessagePageResponse> page = client.get().uri("/api/v1/chats/{id}/messages", chatRoomId)
                .header(HttpHeaders.COOKIE, E2ESession.login(client, userId, Role.INFLUENCER).cookieHeader())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatMessagePageResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(page).isNotNull();
        return page.data().messages().stream().map(ChatMessageResponse::content).toList();
    }

    private StompSession connect(Long userId) throws Exception {
        return connect(userId, new StompEvents());
    }

    private StompSession connect(Long userId, StompEvents events) throws Exception {
        return connect(userId, Role.INFLUENCER, events);
    }

    private StompSession connectFrom(Long userId, String origin) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.COOKIE, E2ESession.login(client, userId, Role.INFLUENCER).cookieHeader());
        handshakeHeaders.setOrigin(origin);
        return stompClient.connectAsync(wsUrl(), handshakeHeaders, new StompHeaders(), new StompSessionHandlerAdapter() {
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 동작을 실행한 뒤 그 회원의 연결이 서버에서 닫힌 상태를 기다린다. 거절된 프레임은 ERROR 프레임이 오지 않을 수 있어(#173)
     * 연결 종료로 확인한다.
     */
    private CloseStatus closeStatusOf(Long userId, ThrowingRunnable action) throws Exception {
        BlockingQueue<CloseStatus> disconnects = new LinkedBlockingQueue<>();
        ApplicationListener<SessionDisconnectEvent> listener = event -> {
            if (event.getUser() != null && String.valueOf(userId).equals(event.getUser().getName())) {
                disconnects.add(event.getCloseStatus());
            }
        };
        applicationContext.addApplicationListener(listener);
        try {
            action.run();
            CloseStatus closeStatus = disconnects.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(closeStatus).isNotNull();
            return closeStatus;
        } finally {
            applicationContext.removeApplicationListener(listener);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private StompSession connect(Long userId, Role role, StompEvents events) throws Exception {
        return connectWith(E2ESession.login(client, userId, role), events);
    }

    private StompSession connectWith(E2ESession login, StompEvents events) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.COOKIE, login.cookieHeader());
        StompSessionHandlerAdapter handler = new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                events.errorFrames.add(String.valueOf(headers.getFirst("message")));
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                events.transportErrors.add(exception);
            }
        };
        return stompClient.connectAsync(wsUrl(), handshakeHeaders, new StompHeaders(), handler)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/api/v1/chats/ws";
    }

    /** 방 topic을 구독하고 메시지 프레임만 모은다(거절·회수 프레임은 {@code chat-frame-type} 헤더로 거른다) */
    private BlockingQueue<ChatMessageResponse> subscribeRoom(StompSession session, Long chatRoomId,
            int participationVersion) {
        BlockingQueue<ChatMessageResponse> queue = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/topic/chats/rooms/" + chatRoomId);
        headers.add("participation-version", String.valueOf(participationVersion));
        session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders frameHeaders) {
                return ChatMessageResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders frameHeaders, Object payload) {
                if (frameHeaders.getFirst("chat-frame-type") == null) {
                    queue.add((ChatMessageResponse) payload);
                }
            }
        });
        return queue;
    }

    private <T> BlockingQueue<T> subscribe(StompSession session, String destination, Class<T> type) {
        BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add(type.cast(payload));
            }
        });
        return queue;
    }

    /**
     * 내장 브로커는 SUBSCRIBE 처리 완료(RECEIPT)를 보내지 않으므로 구독이 등록될 때까지 잠깐 기다린다.
     */
    private void waitForSubscriptions() throws InterruptedException {
        Thread.sleep(300);
    }

    /**
     * 세션 핸들러가 받은 ERROR 프레임 문구와 전송 오류(연결 끊김 등).
     */
    private static class StompEvents {
        private final BlockingQueue<String> errorFrames = new LinkedBlockingQueue<>();
        private final BlockingQueue<Throwable> transportErrors = new LinkedBlockingQueue<>();
    }
}
