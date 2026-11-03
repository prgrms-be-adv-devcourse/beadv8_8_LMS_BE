package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;

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

import jakarta.persistence.EntityManager;

/**
 * 연결별 줄 실행기의 단일 연결 SEND 순서와 여러 연결이 동시에 보낸 메시지의 상대 수신을 실제 STOMP로 검증한다.
 * 순서 검증이 몫 초과 종료와 섞이지 않도록 연결별 몫을 64프레임으로 둔다(운영 기본값과 같다).
 * 이전 실행기와의 관찰용 성능 비교는 회귀 검증에 포함하지 않는다.
 */
@ChatE2ETest
class ChatInboundReceiveOrderE2ETest {

    @DynamicPropertySource
    static void roomyPerSession(DynamicPropertyRegistry registry) {
        registry.add("hapbang.chat.realtime.inbound.per-session-frames", () -> "64");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private LocalChatConnections localChatConnections;

    private ChatNativeSql sql;

    @Autowired
    private ChatInboundReceiveBudget chatInboundReceiveBudget;

    @Autowired
    @Qualifier("clientInboundChannel")
    private ExecutorSubscribableChannel clientInboundChannel;

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
        assertThat(clientInboundChannel.getExecutor()).isSameAs(chatInboundReceiveBudget.channelExecutor());
    }

    @AfterEach
    void tearDown() {
        stomp.close();
    }

    @RepeatedTest(3)
    void 한_연결이_SEND_50개를_연달아_보내면_보낸_순서대로_방_순번을_받는다() throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        StompSession host = stomp.connect(HOST_ID);
        BlockingQueue<RealtimeStompClient.Frame<ChatMessageResponse>> echoes = stomp.subscribe(host,
                "/user/queue/chats/messages", ChatMessageResponse.class);
        waitForSubscriptions();
        List<String> sent = IntStream.rangeClosed(1, 50).mapToObj(i -> "order-" + i).toList();
        for (String clientMessageId : sent) {
            host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest(clientMessageId, clientMessageId));
        }

        await().atMost(Duration.ofSeconds(15)).until(() -> clientMessageIds(chatRoomId).size() == 50);
        assertThat(clientMessageIds(chatRoomId)).containsExactlyElementsOf(sent);
        assertThat(host.isConnected()).isTrue();

        // 보낸 사람이 받은 메시지의 방 순번도 보낸 순서대로 커진다
        Map<String, Long> sequenceByClientId = new ConcurrentHashMap<>();
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            RealtimeStompClient.Frame<ChatMessageResponse> frame;
            while ((frame = echoes.poll()) != null) {
                sequenceByClientId.put(frame.payload().clientMessageId(), frame.payload().roomSequence());
            }
            return sequenceByClientId.size() == 50;
        });
        List<Long> sequencesInSendOrder = sent.stream().map(sequenceByClientId::get).toList();
        assertThat(sequencesInSendOrder).isSorted().doesNotHaveDuplicates();
    }

    /** 9개 연결이 각각 10ms 간격으로 30개씩 보내도 270개 메시지 모두를 발신 회원이 아닌 상대가 수신해야 한다. */
    @Test
    void 여러_연결이_동시에_전송한_메시지_모두를_상대가_수신한다() throws Exception {
        Long room12 = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        Long room32 = chatRoomUseCase.createOrGet(OTHER_INFLUENCER_ID, GUEST_ID).chatRoomId();
        Long room13 = chatRoomUseCase.createOrGet(HOST_ID, OTHER_INFLUENCER_ID).chatRoomId();
        Map<String, Long> receivedAt = new ConcurrentHashMap<>();
        List<StompSession> senders = new ArrayList<>();
        List<Long> rooms = new ArrayList<>();
        Long[][] plan = {{HOST_ID, room12, GUEST_ID}, {GUEST_ID, room32, OTHER_INFLUENCER_ID},
                {OTHER_INFLUENCER_ID, room13, HOST_ID}};
        for (int copy = 0; copy < 3; copy++) {
            for (Long[] row : plan) {
                StompSession session = stomp.connect(row[0]);
                Long userId = row[0];
                // 각 회원이 참여한 두 방을 구독한다. 발신 회원의 다른 연결이 받은 프레임은 상대 수신으로 세지 않는다.
                for (Long[] other : plan) {
                    if (!other[0].equals(userId) && !other[2].equals(userId)) {
                        continue;
                    }
                    StompHeaders headers = new StompHeaders();
                    headers.setDestination("/topic/chats/rooms/" + other[1]);
                    headers.add("participation-version", "1");
                    session.subscribe(headers, new StompFrameHandler() {
                        @Override
                        public java.lang.reflect.Type getPayloadType(StompHeaders frameHeaders) {
                            return ChatMessageResponse.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders frameHeaders, Object payload) {
                            ChatMessageResponse response = (ChatMessageResponse) payload;
                            if (frameHeaders.getFirst("chat-frame-type") == null
                                    && !userId.equals(response.senderUserId())) {
                                // 상대에게는 전송 ID가 없으므로 같은 값을 담은 본문으로 수신을 식별한다.
                                receivedAt.putIfAbsent(response.content(), System.nanoTime());
                            }
                        }
                    });
                }
                senders.add(session);
                rooms.add(row[1]);
            }
        }
        waitForSubscriptions();

        int perSession = 30;
        Set<String> expectedIds = new HashSet<>();
        for (int session = 0; session < senders.size(); session++) {
            for (int message = 0; message < perSession; message++) {
                expectedIds.add("parallel-" + session + "-" + message);
            }
        }
        assertThat(senders).hasSize(9);
        assertThat(expectedIds).hasSize(270);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(senders.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int session = 0; session < senders.size(); session++) {
                int index = session;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int message = 0; message < perSession; message++) {
                        String id = "parallel-" + index + "-" + message;
                        senders.get(index).send("/pub/chats/" + rooms.get(index) + "/messages",
                                new SendChatMessageRequest(id, id));
                        Thread.sleep(10);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> expectedIds.stream().allMatch(receivedAt::containsKey));
        assertThat(receivedAt.keySet()).containsExactlyInAnyOrderElementsOf(expectedIds);
    }

    private List<String> clientMessageIds(Long chatRoomId) {
        return sql.list(
                "select client_message_id from chats.chat_message where chat_room_id = :roomId"
                        + " and client_message_id is not null order by room_sequence", String.class,
                Map.of("roomId", chatRoomId));
    }

}
