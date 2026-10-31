package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
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
import org.springframework.test.util.ReflectionTestUtils;
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
 * 통합 재검증(2026-10-30): 연결별 줄 실행기가 한 연결의 SEND를 보낸 순서대로 처리하는지(방 순번)와, 평소 부하에서 전송부터 상대 수신까지의
 * 지연을 실제 STOMP로 잰다. 순서 검증이 몫 초과 종료와 섞이지 않도록 연결별 몫을 64프레임으로 둔다(운영 기본값과 같다).
 * 비교용으로 수신 채널의 실행기를 공용 실행기 직접(이전 방식: 연결별 순서·몫 없음)으로 잠시 바꿔 같은 부하를 잰다.
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
    private Executor originalExecutor;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        stomp = new RealtimeStompClient(RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(),
                port, localChatConnections);
        originalExecutor = clientInboundChannel.getExecutor();
        assertThat(originalExecutor).isSameAs(chatInboundReceiveBudget.channelExecutor());
    }

    @AfterEach
    void tearDown() {
        useExecutor(originalExecutor);
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

    /** 비교 관찰: 공용 실행기 직접(이전 방식)이면 한 연결의 SEND 50개가 순서를 지키는지. 결과는 로그로만 남긴다 */
    @Test
    void 비교_이전_방식에서는_SEND_50개의_순서가_어떻게_되는지_기록한다() throws Exception {
        useExecutor(chatInboundReceiveBudget.executor());
        int totalInversions = 0;
        for (int round = 0; round < 3; round++) {
            Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
            StompSession host = stomp.connect(HOST_ID);
            waitForSubscriptions();
            String prefix = "old-" + round + "-";
            List<String> sent = IntStream.rangeClosed(1, 50).mapToObj(i -> prefix + i).toList();
            for (String clientMessageId : sent) {
                host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest(clientMessageId,
                        clientMessageId));
            }
            await().atMost(Duration.ofSeconds(15)).until(() -> clientMessageIds(chatRoomId, prefix).size() == 50);
            List<String> stored = clientMessageIds(chatRoomId, prefix);
            int inversions = 0;
            for (int i = 1; i < stored.size(); i++) {
                if (sent.indexOf(stored.get(i)) < sent.indexOf(stored.get(i - 1))) {
                    inversions++;
                }
            }
            totalInversions += inversions;
            host.disconnect();
        }
        System.out.println("ORDER plain-pool (previous) adjacent inversions over 3x50 SENDs: " + totalInversions);
    }

    /**
     * 평소 부하: 회원 셋이 연결을 3개씩(9개) 열고, 각 연결이 10ms 간격으로 30개씩 상대가 있는 방에 보낸다(270개).
     * 전송부터 상대 연결 중 하나가 받을 때까지 지연을 연결별 줄(지금)·공용 실행기 직접(이전)·연결별 줄(다시) 순으로 잰다.
     */
    @Test
    void 평소_부하에서_전송부터_상대_수신까지_지연을_잰다() throws Exception {
        Long room12 = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        Long room32 = chatRoomUseCase.createOrGet(OTHER_INFLUENCER_ID, GUEST_ID).chatRoomId();
        Long room13 = chatRoomUseCase.createOrGet(HOST_ID, OTHER_INFLUENCER_ID).chatRoomId();
        Map<String, Long> receivedAt = new ConcurrentHashMap<>();
        List<StompSession> senders = new ArrayList<>();
        List<Long> rooms = new ArrayList<>();
        List<Long> partners = new ArrayList<>();
        Long[][] plan = {{HOST_ID, room12, GUEST_ID}, {GUEST_ID, room32, OTHER_INFLUENCER_ID},
                {OTHER_INFLUENCER_ID, room13, HOST_ID}};
        for (int copy = 0; copy < 3; copy++) {
            for (Long[] row : plan) {
                StompSession session = stomp.connect(row[0]);
                Long userId = row[0];
                // 상대 메시지는 방 topic으로 온다. 이 회원이 참여한 두 방을 모두 구독한다
                for (Long[] other : plan) {
                    boolean member = other[0].equals(userId) || other[2].equals(userId);
                    if (!member) {
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
                                // 상대 쪽에는 전송 ID가 없으므로 본문에 담은 전송 ID로 찾는다
                                receivedAt.putIfAbsent(response.content(), System.nanoTime());
                            }
                        }
                    });
                }
                senders.add(session);
                rooms.add(row[1]);
                partners.add(row[2]);
            }
        }
        waitForSubscriptions();

        String now = measure("new-a", senders, rooms, receivedAt);
        useExecutor(chatInboundReceiveBudget.executor());
        String before = measure("old", senders, rooms, receivedAt);
        useExecutor(originalExecutor);
        String nowAgain = measure("new-b", senders, rooms, receivedAt);
        System.out.println("LATENCY per-session(1st) " + now);
        System.out.println("LATENCY plain-pool(previous) " + before);
        System.out.println("LATENCY per-session(2nd) " + nowAgain);
        assertThat(partners).hasSize(9);
    }

    private String measure(String phase, List<StompSession> senders, List<Long> rooms, Map<String, Long> receivedAt)
            throws Exception {
        int perSession = 30;
        Map<String, Long> sentAt = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(senders.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int s = 0; s < senders.size(); s++) {
                int index = s;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perSession; i++) {
                        String id = phase + "-" + index + "-" + i;
                        sentAt.put(id, System.nanoTime());
                        senders.get(index).send("/pub/chats/" + rooms.get(index) + "/messages",
                                new SendChatMessageRequest(id, id));
                        Thread.sleep(10);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> sentAt.keySet().stream().allMatch(receivedAt::containsKey));
        long[] latencies = sentAt.entrySet().stream()
                .mapToLong(entry -> TimeUnit.NANOSECONDS.toMicros(receivedAt.get(entry.getKey()) - entry.getValue()))
                .sorted().toArray();
        return String.format("n=%d p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms", latencies.length,
                percentile(latencies, 50) / 1000.0, percentile(latencies, 95) / 1000.0,
                percentile(latencies, 99) / 1000.0, latencies[latencies.length - 1] / 1000.0);
    }

    private static long percentile(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    /** 수신 채널의 실행기를 바꾼다(테스트 비교용). 채널 실행기 필드는 final이라 리플렉션으로 바꾼다 */
    private void useExecutor(Executor executor) {
        ReflectionTestUtils.setField(clientInboundChannel, "executor", executor);
    }

    private List<String> clientMessageIds(Long chatRoomId) {
        return sql.list(
                "select client_message_id from chats.chat_message where chat_room_id = :roomId"
                        + " and client_message_id is not null order by room_sequence", String.class,
                Map.of("roomId", chatRoomId));
    }

    private List<String> clientMessageIds(Long chatRoomId, String prefix) {
        return sql.list(
                "select client_message_id from chats.chat_message where chat_room_id = :roomId"
                        + " and client_message_id like :prefix order by room_sequence", String.class,
                Map.of("roomId", chatRoomId, "prefix", prefix + "%"));
    }
}
