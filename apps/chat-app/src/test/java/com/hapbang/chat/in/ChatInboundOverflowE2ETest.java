package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.hapbang.chat.app.ChatRoomUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.websocket.ChatInboundReceiveBudget;
import com.hapbang.chat.in.websocket.ChatOutboundSendBudget;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.persistence.EntityManager;

/**
 * 수신 예산(clientInboundChannel)을 실제 STOMP로 확인한다. 연결마다 받은 순서대로 하나씩 처리하고, 한 연결이 처리를 시작하지 않은 채
 * 쌓아 둔 프레임이 몫({@code per-session-frames})을 넘으면 <b>그 연결</b>을 {@code 4500 INBOUND_OVERFLOW}로 닫는다.
 * <p>
 * 수신 처리 스레드 1개·공용 대기열 5칸·연결별 몫 2프레임으로 줄이고, 다른 트랜잭션이 방 행을 잠가 둔다. 첫 SEND의 메시지 매핑 처리는
 * 방 잠금을 기다리며 스레드를 붙잡는다. 그 연결의 다음 프레임은 공용 대기열이 아니라 자기 줄에 쌓인다.
 */
@ChatE2ETest
class ChatInboundOverflowE2ETest {

    @DynamicPropertySource
    static void tinyInbound(DynamicPropertyRegistry registry) {
        registry.add("hapbang.chat.realtime.inbound.workers", () -> "1");
        registry.add("hapbang.chat.realtime.inbound.queue-capacity", () -> "5");
        registry.add("hapbang.chat.realtime.inbound.per-session-frames", () -> "2");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private LocalChatConnections localChatConnections;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    private ChatNativeSql sql;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ChatInboundReceiveBudget chatInboundReceiveBudget;

    @Autowired
    private SimpleBrokerMessageHandler simpleBrokerMessageHandler;

    @Autowired
    private MeterRegistry meterRegistry;

    private RealtimeStompClient stomp;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        stomp = new RealtimeStompClient(RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(),
                port, localChatConnections);
    }

    @AfterEach
    void tearDown() {
        stomp.close();
    }

    @Test
    void 한_연결이_몫보다_많은_프레임을_쌓으면_그_연결을_INBOUND_OVERFLOW로_닫고_받은_전송만_보낸_순서대로_저장한다()
            throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        StompSession host = stomp.connect(HOST_ID);
        awaitInboundIdle();
        Map<String, CloseStatus> closes = listenCloses();
        RoomLock lock = lockRoom(chatRoomId);
        try {
            String destination = "/pub/chats/" + chatRoomId + "/messages";
            host.send(destination, new SendChatMessageRequest("첫째", "in-1"));
            host.send(destination, new SendChatMessageRequest("둘째", "in-2"));
            // 첫째(처리 중이지만 남은 처리기 몫이 줄에 있음)·둘째가 몫 2프레임을 채워 셋째에서 넘친다
            host.send(destination, new SendChatMessageRequest("셋째", "in-3"));

            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> closes.containsKey(String.valueOf(HOST_ID)));
            CloseStatus closeStatus = closes.get(String.valueOf(HOST_ID));
            assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.SESSION_NOT_RELIABLE.getCode());
            assertThat(closeStatus.getReason()).isEqualTo("INBOUND_OVERFLOW");
        } finally {
            lock.release();
        }

        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> clientMessageIds(chatRoomId).size() == 2);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> clientMessageIds(chatRoomId).size() == 2);
        // 한 연결의 프레임은 보낸 순서대로 처리된다(방 순번 순으로 in-1, in-2)
        assertThat(clientMessageIds(chatRoomId)).containsExactly("in-1", "in-2");
    }

    /**
     * 통합 검증(2026-10-30)에서 바꾼 동작: 공용 대기열을 함께 쓰더라도 넘침의 책임은 프레임을 쏟아낸 연결이 진다. 쏟아낸 연결만 닫히고,
     * 그 순간 다른 방에 보낸 다른 회원의 전송은 차례를 기다렸다가 처리되어 상대에게 실시간으로 간다. 닫힌 회원은 재연결 뒤 버려진
     * 전송을 같은 전송 ID로 다시 보내 한 번만 저장되며, 수신 넘침은 송신 예산에 영향을 주지 않는다. 닫힌 연결의 구독은 내장 브로커에서
     * 지워진다(세션 종료 정리는 버리지 않는다).
     */
    @Test
    void 프레임을_쏟아낸_연결만_닫히고_그_순간_다른_방에_보낸_회원의_전송은_처리되며_닫힌_회원은_재연결해_다시_보낸다()
            throws Exception {
        Long lockedRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        Long otherRoomId = chatRoomUseCase.createOrGet(OTHER_INFLUENCER_ID, GUEST_ID).chatRoomId();
        StompSession flooder = stomp.connect(HOST_ID);
        awaitInboundIdle();
        StompSession bystander = stomp.connect(GUEST_ID);
        awaitInboundIdle();
        StompSession partner = stomp.connect(OTHER_INFLUENCER_ID);
        awaitInboundIdle();
        stomp.subscribe(flooder, "/user/queue/chats/messages", ChatMessageResponse.class);
        stomp.subscribe(bystander, "/user/queue/chats/messages", ChatMessageResponse.class);
        BlockingQueue<RealtimeStompClient.Frame<ChatMessageResponse>> partnerMessages =
                stomp.subscribeRoomMessages(partner, otherRoomId, 1);
        waitForSubscriptions();
        awaitInboundIdle();
        Map<String, CloseStatus> closes = listenCloses();
        Map<String, String> closedSessionIds = new ConcurrentHashMap<>();
        ApplicationListener<SessionDisconnectEvent> sessionIds = event -> {
            if (event.getUser() != null) {
                closedSessionIds.putIfAbsent(event.getUser().getName(), event.getSessionId());
            }
        };
        applicationContext.addApplicationListener(sessionIds);
        double outboundRejectedBefore = meterRegistry.counter(ChatOutboundSendBudget.REJECTED_METRIC).count();
        double inboundRejectedBefore = meterRegistry.counter(ChatInboundReceiveBudget.REJECTED_METRIC).count();
        RoomLock lock = lockRoom(lockedRoomId);
        try {
            String flooded = "/pub/chats/" + lockedRoomId + "/messages";
            flooder.send(flooded, new SendChatMessageRequest("첫째", "flood-1"));
            flooder.send(flooded, new SendChatMessageRequest("둘째", "flood-2"));
            flooder.send(flooded, new SendChatMessageRequest("셋째", "flood-3"));

            // 프레임을 쏟아낸 회원의 연결이 닫힌다
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> closes.containsKey(String.valueOf(HOST_ID)));
            CloseStatus flooderClose = closes.get(String.valueOf(HOST_ID));
            assertThat(flooderClose.getCode()).isEqualTo(CloseStatus.SESSION_NOT_RELIABLE.getCode());
            assertThat(flooderClose.getReason()).isEqualTo("INBOUND_OVERFLOW");

            // 그 순간 다른 방에 보낸 회원의 프레임은 버려지지 않고 차례를 기다린다
            bystander.send("/pub/chats/" + otherRoomId + "/messages", new SendChatMessageRequest("무관한 방", "by-1"));
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(bystander::isConnected);
        } finally {
            lock.release();
        }
        try {
            // 다른 회원의 전송은 처리되어 상대에게 실시간으로 간다. 쏟아낸 연결의 받은 두 전송은 저장되고, 버린 셋째는 저장되지 않는다
            RealtimeStompClient.Frame<ChatMessageResponse> received = partnerMessages.poll(15, TimeUnit.SECONDS);
            assertThat(received).isNotNull();
            assertThat(received.payload().content()).isEqualTo("무관한 방");
            assertThat(clientMessageIds(otherRoomId)).containsExactly("by-1");
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .until(() -> clientMessageIds(lockedRoomId).size() == 2);
            assertThat(clientMessageIds(lockedRoomId)).containsExactly("flood-1", "flood-2");
            assertThat(closes).doesNotContainKeys(String.valueOf(GUEST_ID), String.valueOf(OTHER_INFLUENCER_ID));
            assertThat(bystander.isConnected()).isTrue();
            assertThat(partner.isConnected()).isTrue();

            // 닫힌 연결의 구독은 내장 브로커에서 지워진다(세션 종료 정리 DISCONNECT를 버리지 않는다)
            awaitInboundIdle();
            String flooderSessionId = closedSessionIds.get(String.valueOf(HOST_ID));
            assertThat(flooderSessionId).isNotNull();
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .until(() -> subscriptionsOf(flooderSessionId).isEmpty());

            // 닫힌 회원은 재연결해 버려진 전송을 같은 전송 ID로 다시 보낸다. 한 번만 저장된다
            StompSession reconnected = stomp.connect(HOST_ID);
            awaitInboundIdle();
            reconnected.send("/pub/chats/" + lockedRoomId + "/messages", new SendChatMessageRequest("셋째", "flood-3"));
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .until(() -> clientMessageIds(lockedRoomId).size() == 3);
            assertThat(clientMessageIds(lockedRoomId)).containsExactly("flood-1", "flood-2", "flood-3");
            assertThat(meterRegistry.counter(ChatOutboundSendBudget.REJECTED_METRIC).count())
                    .isEqualTo(outboundRejectedBefore);
            assertThat(meterRegistry.counter(ChatInboundReceiveBudget.REJECTED_METRIC).count())
                    .isGreaterThan(inboundRejectedBefore);
            awaitInboundIdle();
        } finally {
            applicationContext.removeApplicationListener(sessionIds);
        }
    }

    /** 회원 ID별 처음 닫힌 상태. 테스트 동안만 듣는다 */
    private Map<String, CloseStatus> listenCloses() {
        Map<String, CloseStatus> closes = new ConcurrentHashMap<>();
        ApplicationListener<SessionDisconnectEvent> listener = event -> {
            if (event.getUser() != null) {
                closes.putIfAbsent(event.getUser().getName(), event.getCloseStatus());
            }
        };
        applicationContext.addApplicationListener(listener);
        listeners.add(listener);
        return closes;
    }

    private final List<ApplicationListener<SessionDisconnectEvent>> listeners = new CopyOnWriteArrayList<>();

    @AfterEach
    void removeListeners() {
        listeners.forEach(applicationContext::removeApplicationListener);
        listeners.clear();
    }

    /** 다른 트랜잭션이 방 행을 잠가 둔다. 그 방의 전송은 잠금을 기다리며 수신 처리 스레드를 붙잡는다 */
    private RoomLock lockRoom(Long chatRoomId) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate
                .executeWithoutResult(status -> {
                    sql.single("select id from chats.chat_room where id = :id for update", Long.class,
                            Map.of("id", chatRoomId));
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return new RoomLock(release, holder);
    }

    private record RoomLock(CountDownLatch unlock, CompletableFuture<Void> holder) {

        void release() throws Exception {
            unlock.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    /** 내장 브로커에 남은 그 연결의 회원 메시지 큐 구독 */
    private Map<String, List<String>> subscriptionsOf(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination("/queue/chats/messages-user" + sessionId);
        return simpleBrokerMessageHandler.getSubscriptionRegistry()
                .findSubscriptions(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()));
    }

    private void awaitInboundIdle() {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> chatInboundReceiveBudget.executor().getActiveCount() == 0
                        && chatInboundReceiveBudget.executor().getQueueSize() == 0
                        && chatInboundReceiveBudget.sessionsWithQueuedWork() == 0);
    }

    private List<String> clientMessageIds(Long chatRoomId) {
        return sql.list(
                "select client_message_id from chats.chat_message where chat_room_id = :roomId"
                        + " and client_message_id is not null order by room_sequence", String.class,
                Map.of("roomId", chatRoomId));
    }
}
