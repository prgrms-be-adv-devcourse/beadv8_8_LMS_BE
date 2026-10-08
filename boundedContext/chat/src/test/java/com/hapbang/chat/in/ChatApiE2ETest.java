package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.hapbang.chat.app.ChatUserService;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.dto.ChatErrorMessage;
import com.hapbang.chat.in.dto.ChatNotification;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/**
 * 실제 서버를 띄워 REST(RestTestClient)와 STOMP(WebSocketStompClient)로 채팅 흐름을 검증한다.
 */
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.modulith.events.completion-mode=delete"
        })
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class ChatApiE2ETest {

    private static final long TIMEOUT_SECONDS = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserService chatUserService;

    private RestTestClient client;
    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserService);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    @Test
    void 채팅방을_만들면_201_다시_요청하면_같은_방으로_200() {
        ChatRoomResponse created = createRoom(HOST_ID, GUEST_ID, HttpStatus.CREATED);

        ChatRoomResponse reused = createRoom(GUEST_ID, HOST_ID, HttpStatus.OK);

        assertThat(reused.chatRoomId()).isEqualTo(created.chatRoomId());
    }

    @Test
    void 일반_유저가_채팅방을_만들면_403_ProblemDetail() {
        ProblemDetail problem = client.post().uri("/api/v1/chatRooms")
                .header(ChatHeaders.USER_ID, String.valueOf(USER_ID))
                .body(new CreateChatRoomRequest(HOST_ID))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "CHAT_NOT_ALLOWED");
    }

    @Test
    void 채팅_상대_ID가_없으면_400_ProblemDetail에_항목별_오류를_담는다() {
        ProblemDetail problem = client.post().uri("/api/v1/chatRooms")
                .header(ChatHeaders.USER_ID, String.valueOf(HOST_ID))
                .body(new CreateChatRoomRequest(null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
        assertThat(problem.getProperties().get("errors")).asInstanceOf(LIST)
                .containsExactly(Map.of("field", "targetUserId", "message", "채팅 상대 회원 ID는 필수입니다."));
    }

    @Test
    void 메시지_조회_size가_범위를_벗어나면_400() {
        ChatRoomResponse room = createRoom(HOST_ID, GUEST_ID, HttpStatus.CREATED);

        ProblemDetail problem = client.get().uri("/api/v1/chatRooms/{id}/messages?size=0", room.chatRoomId())
                .header(ChatHeaders.USER_ID, String.valueOf(HOST_ID))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
        assertThat(problem.getProperties().get("errors")).asInstanceOf(LIST)
                .containsExactly(Map.of("field", "size", "message", "size는 1 이상이어야 합니다."));
    }

    @Test
    void 회원_ID_헤더가_없으면_400() {
        client.post().uri("/api/v1/chatRooms")
                .body(new CreateChatRoomRequest(GUEST_ID))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void 방을_나가면_204_다시_보내도_204_이후_메시지_조회는_403() {
        ChatRoomResponse room = createRoom(HOST_ID, GUEST_ID, HttpStatus.CREATED);

        for (int i = 0; i < 2; i++) {
            client.delete().uri("/api/v1/chatRooms/{id}/members/me", room.chatRoomId())
                    .header(ChatHeaders.USER_ID, String.valueOf(GUEST_ID))
                    .exchange()
                    .expectStatus().isNoContent();
        }

        client.get().uri("/api/v1/chatRooms/{id}/messages", room.chatRoomId())
                .header(ChatHeaders.USER_ID, String.valueOf(GUEST_ID))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void 관리자가_아니면_기록_조회는_403() {
        ChatRoomResponse room = createRoom(HOST_ID, GUEST_ID, HttpStatus.CREATED);

        client.get().uri("/api/v1/chatRooms/{id}/history", room.chatRoomId())
                .header(ChatHeaders.USER_ID, String.valueOf(HOST_ID))
                .exchange()
                .expectStatus().isForbidden();

        client.get().uri("/api/v1/chatRooms/{id}/history", room.chatRoomId())
                .header(ChatHeaders.USER_ID, String.valueOf(ADMIN_ID))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void STOMP로_보낸_메시지가_두_사람에게_전달되고_상대만_알림을_받으며_DB에_저장된다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID, HttpStatus.CREATED).chatRoomId();
        StompSession host = connect(HOST_ID);
        StompSession guest = connect(GUEST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatMessageResponse> guestMessages = subscribe(guest, "/user/queue/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatNotification> guestNotifications = subscribe(guest,
                "/user/queue/notifications", ChatNotification.class);
        BlockingQueue<ChatNotification> hostNotifications = subscribe(host,
                "/user/queue/notifications", ChatNotification.class);
        waitForSubscriptions();

        host.send("/pub/chatRooms/" + chatRoomId + "/messages", new SendChatMessageRequest("금요일 밤 9시 괜찮으세요?"));

        ChatMessageResponse received = guestMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.content()).isEqualTo("금요일 밤 9시 괜찮으세요?");
        assertThat(received.senderUserId()).isEqualTo(HOST_ID);
        assertThat(received.roomSequence()).isEqualTo(1);
        assertThat(hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .extracting(ChatMessageResponse::messageId).isEqualTo(received.messageId());

        ChatNotification notification = guestNotifications.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(notification).isNotNull();
        assertThat(notification.type()).isEqualTo(ChatNotification.NotificationType.NEW_MESSAGE);
        assertThat(notification.messageId()).isEqualTo(received.messageId());
        assertThat(hostNotifications.poll(500, TimeUnit.MILLISECONDS)).isNull();

        ChatMessagePageResponse page = client.get().uri("/api/v1/chatRooms/{id}/messages", chatRoomId)
                .header(ChatHeaders.USER_ID, String.valueOf(GUEST_ID))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatMessagePageResponse.class)
                .returnResult().getResponseBody();
        assertThat(page).isNotNull();
        assertThat(page.messages()).extracting(ChatMessageResponse::content)
                .containsExactly("금요일 밤 9시 괜찮으세요?");
    }

    @Test
    void 상대가_방을_나가면_남은_사람에게만_퇴장_안내가_실시간으로_가고_남은_사람은_보낼_수_없다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID, HttpStatus.CREATED).chatRoomId();
        StompSession host = connect(HOST_ID);
        StompSession guest = connect(GUEST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatMessageResponse> guestMessages = subscribe(guest, "/user/queue/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatErrorMessage> hostErrors = subscribe(host, "/user/queue/errors", ChatErrorMessage.class);
        waitForSubscriptions();

        client.delete().uri("/api/v1/chatRooms/{id}/members/me", chatRoomId)
                .header(ChatHeaders.USER_ID, String.valueOf(GUEST_ID))
                .exchange()
                .expectStatus().isNoContent();

        ChatMessageResponse leftNotice = hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(leftNotice).isNotNull();
        assertThat(leftNotice.messageType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(leftNotice.content()).isEqualTo("게스트님이 나갔습니다.");
        assertThat(guestMessages.poll(500, TimeUnit.MILLISECONDS)).isNull();

        host.send("/pub/chatRooms/" + chatRoomId + "/messages", new SendChatMessageRequest("아직 계세요?"));

        ChatErrorMessage error = hostErrors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("CHAT_PARTNER_LEFT");
        assertThat(guestMessages.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    // 수용 테스트 1: 화면 닫기(연결 끊김)는 퇴장이 아니다.
    @Test
    void 연결이_끊겨도_퇴장으로_처리하지_않는다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID, HttpStatus.CREATED).chatRoomId();
        StompSession guest = connect(GUEST_ID);
        guest.disconnect();
        StompSession host = connect(HOST_ID);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/messages",
                ChatMessageResponse.class);
        waitForSubscriptions();

        host.send("/pub/chatRooms/" + chatRoomId + "/messages", new SendChatMessageRequest("다시 오면 보세요"));

        assertThat(hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        ChatMessagePageResponse guestPage = client.get().uri("/api/v1/chatRooms/{id}/messages", chatRoomId)
                .header(ChatHeaders.USER_ID, String.valueOf(GUEST_ID))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatMessagePageResponse.class)
                .returnResult().getResponseBody();
        assertThat(guestPage).isNotNull();
        assertThat(guestPage.messages()).extracting(ChatMessageResponse::content).containsExactly("다시 오면 보세요");
    }

    @Test
    void STOMP로_빈_메시지를_보내면_보낸_사람에게_오류가_간다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID, HttpStatus.CREATED).chatRoomId();
        StompSession host = connect(HOST_ID);
        BlockingQueue<ChatErrorMessage> errors = subscribe(host, "/user/queue/errors", ChatErrorMessage.class);
        waitForSubscriptions();

        host.send("/pub/chatRooms/" + chatRoomId + "/messages", new SendChatMessageRequest(" "));

        ChatErrorMessage error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("INVALID_REQUEST");
        assertThat(error.message()).isEqualTo("메시지 내용이 비어 있습니다.");
    }

    @Test
    void 회원_큐가_아닌_경로는_구독할_수_없다() throws Exception {
        BlockingQueue<String> errorFrames = new LinkedBlockingQueue<>();
        StompSession outsider = connect(OTHER_INFLUENCER_ID, errorFrames);

        outsider.subscribe("/queue/messages", new StompSessionHandlerAdapter() {
        });

        String error = errorFrames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(error).isNotNull().contains("구독할 수 없는 경로");
    }

    private ChatRoomResponse createRoom(Long userId, Long targetUserId, HttpStatus expectedStatus) {
        ChatRoomResponse room = client.post().uri("/api/v1/chatRooms")
                .header(ChatHeaders.USER_ID, String.valueOf(userId))
                .body(new CreateChatRoomRequest(targetUserId))
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(ChatRoomResponse.class)
                .returnResult().getResponseBody();
        assertThat(room).isNotNull();
        return room;
    }

    private StompSession connect(Long userId) throws Exception {
        return connect(userId, new LinkedBlockingQueue<>());
    }

    private StompSession connect(Long userId, BlockingQueue<String> errorFrames) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(ChatHeaders.USER_ID, String.valueOf(userId));
        StompSessionHandlerAdapter handler = new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errorFrames.add(String.valueOf(headers.getFirst("message")));
            }
        };
        return stompClient.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                connectHeaders, handler).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
}
