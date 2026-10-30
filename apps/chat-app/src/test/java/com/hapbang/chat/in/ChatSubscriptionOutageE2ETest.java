package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.chat.app.ChatJobStream;
import com.hapbang.chat.app.ChatRoomUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatResyncReason;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.dto.ChatSyncSignal;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.support.RealtimeStompClient.Frame;

/**
 * 이 앱의 Redis 구독이 끊긴 동안 보낸 메시지가 격리되지 않고 기다렸다가, 구독이 돌아오면 한 번씩 전달되는지 실제 경로로 검증한다
 * (전달 파이프라인의 구독자 0명 멈춤·재확인 발행 + 실시간 전파의 재구독 감지).
 * <p>
 * 구독 컨테이너를 멈춰 자기 구독을 끊는다(PUBLISH 구독자 0명). 그동안 보낸 메시지는 발행 시도에 세지 않고 PENDING으로 남으며 DEAD가
 * 되지 않는다. 구독을 다시 시작하면 재구독 감지로 재동기화 신호를 보내고, 재확인 발행이 구독자 1명에 닿아 큐가 이어 가며, 모든
 * 메시지가 상대에게 한 번씩 도착한다. 재확인·회수 간격은 짧게 줄인다.
 */
@ChatE2ETest
class ChatSubscriptionOutageE2ETest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String MESSAGES = "/user/queue/chats/messages";

    @DynamicPropertySource
    static void fastRecovery(DynamicPropertyRegistry registry) {
        registry.add("hapbang.chat.delivery.pause.probe-initial", () -> "200ms");
        registry.add("hapbang.chat.delivery.pause.probe-max", () -> "500ms");
        registry.add("hapbang.chat.delivery.reclaim.interval", () -> "200ms");
        registry.add("hapbang.chat.delivery.reclaim.min-idle", () -> "500ms");
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
    @Qualifier("chatRealtimeListenerContainer")
    private RedisMessageListenerContainer listenerContainer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RealtimeStompClient stomp;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        stomp = new RealtimeStompClient(RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(),
                port, localChatConnections);
    }

    @AfterEach
    void tearDown() {
        if (!listenerContainer.isRunning()) {
            listenerContainer.start();
        }
        stomp.close();
    }

    @Test
    void 구독이_끊긴_동안_보낸_메시지는_격리되지_않고_구독이_돌아오면_한_번씩_전달된다() throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        StompSession host = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        BlockingQueue<Frame<ChatMessageResponse>> guestMessages = stomp.subscribeRoomMessages(guest, chatRoomId, 1);
        BlockingQueue<Frame<ChatSyncSignal>> guestSync = stomp.subscribe(guest, "/user/queue/chats/sync",
                ChatSyncSignal.class);
        waitForSubscriptions();

        listenerContainer.stop();
        for (int i = 1; i <= 3; i++) {
            host.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("끊긴 동안 " + i, "out-" + i));
        }
        // 발행이 구독자 0명을 보고 멈췄다(시도로 세지 않음). 잠시 더 끊어 둬도 격리되지 않는다
        await().atMost(WAIT).until(() -> zeroSubscriberAttempts() >= 1);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> true);
        assertThat(deadCount()).isZero();
        assertThat(attemptSum()).isZero();
        assertThat(guestMessages.poll(100, TimeUnit.MILLISECONDS)).isNull();

        listenerContainer.start();

        List<Long> received = new ArrayList<>();
        await().atMost(WAIT).until(() -> {
            Frame<ChatMessageResponse> frame;
            while ((frame = guestMessages.poll()) != null) {
                received.add(frame.payload().messageId());
            }
            return received.size() >= 3;
        });
        // 한 번씩만: 잠시 더 지켜봐도 중복이 오지 않는다
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).until(() -> true);
        Frame<ChatMessageResponse> extra;
        while ((extra = guestMessages.poll()) != null) {
            received.add(extra.payload().messageId());
        }
        assertThat(received).hasSize(3).doesNotHaveDuplicates();
        assertThat(published()).isEqualTo(3);
        assertThat(deadCount()).isZero();
        assertThat(attemptSum()).isZero();
        // 재구독 감지로 재동기화 신호도 갔다
        Frame<ChatSyncSignal> resync = guestSync.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(resync).isNotNull();
        assertThat(resync.payload().reason()).isEqualTo(ChatResyncReason.REALTIME_RESUBSCRIBED);
    }

    private long zeroSubscriberAttempts() {
        return count("select count(*) from chats.chat_publish_checkpoint where consumer_group = ?"
                + " and last_subscriber_count = 0");
    }

    private long published() {
        return count("select count(*) from chats.chat_publish_checkpoint where consumer_group = ?"
                + " and status = 'PUBLISHED'");
    }

    private long deadCount() {
        return count("select count(*) from chats.chat_publish_checkpoint where consumer_group = ?"
                + " and status = 'DEAD'");
    }

    private long attemptSum() {
        return count("select coalesce(sum(attempt_count), 0) from chats.chat_publish_checkpoint"
                + " where consumer_group = ?");
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, ChatJobStream.DELIVERY.consumerGroup());
        return value == null ? 0 : value;
    }
}
