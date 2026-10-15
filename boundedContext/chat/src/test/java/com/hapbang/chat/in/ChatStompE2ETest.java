package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Type;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.dto.ChatErrorMessage;
import com.hapbang.chat.in.dto.ChatNotification;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.global.response.RsData;
import com.hapbang.shared.member.domain.Role;

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
    void STOMP로_보낸_메시지가_두_사람에게_전달되고_상대만_알림을_받으며_DB에_저장된다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = connect(HOST_ID);
        StompSession guest = connect(GUEST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatMessageResponse> guestMessages = subscribe(guest, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatNotification> guestNotifications = subscribe(guest,
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
        assertThat(received.clientMessageId()).isEqualTo("client-1");
        assertThat(hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .extracting(ChatMessageResponse::messageId, ChatMessageResponse::clientMessageId)
                .containsExactly(received.messageId(), "client-1");

        ChatNotification notification = guestNotifications.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(notification).isNotNull();
        assertThat(notification.type()).isEqualTo(ChatNotification.NotificationType.NEW_MESSAGE);
        assertThat(notification.messageId()).isEqualTo(received.messageId());
        assertThat(hostNotifications.poll(500, TimeUnit.MILLISECONDS)).isNull();

        assertThat(messageContents(chatRoomId, GUEST_ID)).containsExactly("금요일 밤 9시 괜찮으세요?");
    }

    @Test
    void 상대가_방을_나가면_남은_사람에게만_퇴장_안내가_실시간으로_가고_남은_사람은_보낼_수_없다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = connect(HOST_ID);
        StompSession guest = connect(GUEST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatMessageResponse> guestMessages = subscribe(guest, "/user/queue/chats/messages",
                ChatMessageResponse.class);
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
        StompSession host = connect(HOST_ID);
        StompSession guest = connect(GUEST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatMessageResponse> guestMessages = subscribe(guest, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        waitForSubscriptions();

        client.post().uri("/api/v1/chats")
                .headers(E2ESession.login(client, GUEST_ID, Role.VERIFIED_INFLUENCER)::applyTo)
                .body(new CreateChatRoomRequest(HOST_ID))
                .exchange()
                .expectStatus().isOk();

        ChatMessageResponse rejoinNotice = hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejoinNotice).isNotNull();
        assertThat(rejoinNotice.messageType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(rejoinNotice.content()).isEqualTo("게스트님이 다시 들어왔습니다.");
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
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/chats/messages",
                ChatMessageResponse.class);
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
    void 허용하지_않은_출처에서는_웹소켓에_연결할_수_없다() {
        assertThatThrownBy(() -> connectFrom(HOST_ID, "https://evil.example.com"))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void 허용한_출처에서는_웹소켓에_연결할_수_있다() throws Exception {
        assertThat(connectFrom(HOST_ID, "http://localhost:5173").isConnected()).isTrue();
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
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.COOKIE, E2ESession.login(client, userId, role).cookieHeader());
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
