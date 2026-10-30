package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatReceiptResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.ReceivedChatRoomRequest;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.shared.member.domain.Role;

/**
 * 실제 서버의 STOMP 전달과 수신 확인의 관계.
 * <ul>
 *     <li>서버가 메시지를 전달(발행)한 것만으로 수신 확인 순번을 올리지 않는다. 클라이언트 ACK로만 오른다.</li>
 *     <li>ACK는 방의 다른 참여자나 같은 회원의 다른 세션에 아무 프레임도 보내지 않는다(ACK별 방 전체 재방송 없음).</li>
 * </ul>
 */
@ChatE2ETest
class ChatReceiptStompE2ETest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final Duration QUIET_PERIOD = Duration.ofMillis(700);

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    private RestTestClient client;
    private WebSocketStompClient stompClient;
    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        chatRoomId = createRoom();
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    @Test
    void 서버가_메시지를_전달해도_수신_확인_순번은_오르지_않고_클라이언트_ACK로만_오른다() throws Exception {
        E2ESession guestLogin = login(GUEST_ID);
        StompSession host = connect(login(HOST_ID));
        StompSession guest = connect(guestLogin);
        BlockingQueue<ChatMessageResponse> guestMessages = subscribe(guest, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        BlockingQueue<ChatMessageResponse> hostMessages = subscribe(host, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        waitForSubscriptions();

        host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("전달만 된 메시지", "c-1"));

        ChatMessageResponse delivered = guestMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(delivered).isNotNull();
        assertThat(hostMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        // 전달 완료 뒤에도 일정 시간 동안 두 참여자의 수신 확인 순번은 미확인이다
        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.plusSeconds(2)).untilAsserted(() -> {
            assertThat(lastReceivedSequence(GUEST_ID)).isNull();
            assertThat(lastReceivedSequence(HOST_ID)).isNull();
        });

        ChatReceiptResponse ack = markReceived(guestLogin, new ReceivedChatRoomRequest(1, delivered.roomSequence()));
        assertThat(ack.lastReceivedSequence()).isEqualTo(delivered.roomSequence());
        assertThat(lastReceivedSequence(GUEST_ID)).isEqualTo(delivered.roomSequence());
        assertThat(lastReceivedSequence(HOST_ID)).isNull();
    }

    @Test
    void 수신_확인은_상대나_같은_회원의_다른_세션에_아무_프레임도_보내지_않는다() throws Exception {
        E2ESession guestLogin = login(GUEST_ID);
        StompSession host = connect(login(HOST_ID));
        StompSession guestPhone = connect(guestLogin);
        StompSession guestLaptop = connect(login(GUEST_ID));
        List<BlockingQueue<Object>> queues = List.of(
                subscribe(host, "/user/queue/chats/messages", Object.class),
                subscribe(host, "/user/queue/chats/notifications", Object.class),
                subscribe(guestPhone, "/user/queue/chats/messages", Object.class),
                subscribe(guestPhone, "/user/queue/chats/notifications", Object.class),
                subscribe(guestLaptop, "/user/queue/chats/messages", Object.class),
                subscribe(guestLaptop, "/user/queue/chats/notifications", Object.class));
        waitForSubscriptions();

        host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("메시지", "c-1"));
        // 메시지 전달·알림이 모두 도착한 뒤 큐를 비운다. 기록기가 프레임을 실제로 받는다는 대조군이기도 하다
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> queues.get(0).size() == 1 && queues.get(2).size() == 1 && queues.get(3).size() == 1
                        && queues.get(4).size() == 1 && queues.get(5).size() == 1);
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(() -> queues.stream().mapToInt(BlockingQueue::size).sum() == 5);
        queues.forEach(BlockingQueue::clear);

        markReceived(guestLogin, new ReceivedChatRoomRequest(1, 1L));
        markReceived(login(HOST_ID), new ReceivedChatRoomRequest(1, 1L));

        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.plusSeconds(2))
                .until(() -> queues.stream().allMatch(BlockingQueue::isEmpty));
    }

    private Long createRoom() {
        RsData<ChatRoomResponse> room = client.post().uri("/api/v1/chats")
                .headers(login(HOST_ID)::applyTo)
                .body(new CreateChatRoomRequest(GUEST_ID))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<RsData<ChatRoomResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(room).isNotNull();
        return room.data().chatRoomId();
    }

    private ChatReceiptResponse markReceived(E2ESession session, ReceivedChatRoomRequest request) {
        RsData<ChatReceiptResponse> response = client.put().uri("/api/v1/chats/{id}/received", chatRoomId)
                .headers(session::applyTo)
                .body(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatReceiptResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response.data();
    }

    private Long lastReceivedSequence(Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .orElseThrow().getLastReceivedSequence();
    }

    private E2ESession login(Long userId) {
        return E2ESession.login(client, userId, Role.INFLUENCER);
    }

    private StompSession connect(E2ESession login) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add(HttpHeaders.COOKIE, login.cookieHeader());
        return stompClient.connectAsync("ws://localhost:" + port + "/api/v1/chats/ws", handshakeHeaders,
                        new StompHeaders(), new StompSessionHandlerAdapter() {
                        })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private <T> BlockingQueue<T> subscribe(StompSession session, String destination, Class<T> type) {
        BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type == Object.class ? byte[].class : type;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((T) payload);
            }
        });
        return queue;
    }

    /** 내장 브로커는 SUBSCRIBE 처리 완료(RECEIPT)를 보내지 않으므로 구독이 등록될 때까지 잠깐 기다린다 */
    private void waitForSubscriptions() throws InterruptedException {
        Thread.sleep(300);
    }
}
