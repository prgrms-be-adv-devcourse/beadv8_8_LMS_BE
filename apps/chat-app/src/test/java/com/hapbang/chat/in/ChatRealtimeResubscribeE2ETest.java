package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.RedisClientInfo;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.RealtimeChatFanout;
import com.hapbang.chat.app.dto.ChatResyncReason;
import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.dto.ChatSyncSignal;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.support.RealtimeStompClient.Frame;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * Redis 구독이 끊겼다가 다시 연결되면 로컬 연결에 재동기화 신호를 보내고 로컬 연결 회원을 다시 검사하는지 검증한다.
 * <p>
 * 구독 단절은 이 컨텍스트의 Pub/Sub 연결만 서버에서 끊어(CLIENT KILL) 만든다. 공유 Redis 컨테이너를 멈추거나 다른 테스트
 * 컨텍스트의 연결을 끊지 않도록, 이 컨텍스트의 Redis 연결에 고유한 클라이언트 이름을 붙이고 그 이름의 구독 연결만 끊는다.
 * Lettuce가 다시 연결하면서 채널을 다시 구독하고, 그 구독 확인이 재구독 감지로 이어진다(컨테이너를 직접 멈추는 방식은
 * Lettuce 재연결 경로를 거치지 않는다). 연결을 끊은 컨텍스트는 다시 쓰지 않도록 닫는다.
 */
@ChatE2ETest
@Import(RedisTestcontainersConfig.class)
@DirtiesContext
class ChatRealtimeResubscribeE2ETest {

    private static final String NAMESPACE = "test-realtime-resubscribe-" + UUID.randomUUID();

    @DynamicPropertySource
    static void realtimeProperties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.chat.realtime.enabled", () -> "true");
        // 이 테스트는 구독자만 검증한다. 발행은 테스트가 직접 하므로 outbox 파이프라인은 끈다
        registry.add("hapbang.chat.delivery.pipeline.enabled", () -> "false");
        registry.add("hapbang.chat.redis.namespace", () -> NAMESPACE);
        registry.add("spring.data.redis.client-name", () -> NAMESPACE);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private LocalChatConnections localChatConnections;

    @Autowired
    private RealtimeChatFanout realtimeChatFanout;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private RealtimeStompClient stomp;

    @BeforeEach
    void setUp() {
        // 앞 테스트의 연결이 남아 있으면 연결 종료·재동기화 검증이 섞인다
        assertThat(localChatConnections.connectedUserIds()).isEmpty();
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        stomp = new RealtimeStompClient(RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(),
                port, localChatConnections);
    }

    @AfterEach
    void tearDown() {
        stomp.close();
    }

    @Test
    void 구독이_끊겼다가_다시_연결되면_모든_로컬_연결에_재동기화_신호를_한_번_보낸다() throws Exception {
        StompSession hostDevice1 = stomp.connect(HOST_ID);
        StompSession hostDevice2 = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        List<BlockingQueue<Frame<ChatSyncSignal>>> syncQueues = List.of(
                stomp.subscribe(hostDevice1, "/user/queue/chats/sync", ChatSyncSignal.class),
                stomp.subscribe(hostDevice2, "/user/queue/chats/sync", ChatSyncSignal.class),
                stomp.subscribe(guest, "/user/queue/chats/sync", ChatSyncSignal.class));
        waitForSubscriptions();
        // 시작 구독은 재동기화 대상이 아니다
        assertThat(syncQueues.getFirst().poll(300, TimeUnit.MILLISECONDS)).isNull();

        killOwnSubscriptionConnection();

        for (BlockingQueue<Frame<ChatSyncSignal>> queue : syncQueues) {
            Frame<ChatSyncSignal> frame = queue.poll(10, TimeUnit.SECONDS);
            assertThat(frame).as("재동기화 신호").isNotNull();
            assertThat(frame.payload().type()).isEqualTo(ChatSyncSignal.SignalType.RESYNC);
            assertThat(frame.payload().reason()).isEqualTo(ChatResyncReason.REALTIME_RESUBSCRIBED);
            assertThat(frame.payload().occurredAt()).isNotNull();
        }
        assertThat(syncQueues.getFirst().poll(500, TimeUnit.MILLISECONDS)).isNull();
        // 다시 구독했으므로 발행이 이 앱에 도달한다
        await().atMost(Duration.ofSeconds(10)).until(() -> realtimeChatFanout.publishControl(
                new ControlFanout(UUID.randomUUID(), ChatControlType.RECHECK_USER, UNKNOWN_ID, null,
                        ChatControlReason.MEMBER_MODIFIED)) == 1);
    }

    @Test
    void 구독이_다시_연결되면_놓친_제어_명령_없이도_로컬_연결_회원을_곧바로_재검사한다() throws Exception {
        StompSession suspended = stomp.connect(HOST_ID);
        StompSession allowed = stomp.connect(GUEST_ID);
        // 회원 이벤트도 제어 명령도 없이 상태만 바뀐 경우(구독 단절 중 놓친 명령에 해당)
        chatUserUseCase.update(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.SUSPENDED));
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(suspended::isConnected);

        killOwnSubscriptionConnection();

        await().atMost(Duration.ofSeconds(10)).until(() -> !suspended.isConnected());
        assertThat(allowed.isConnected()).isTrue();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> localChatConnections.connectedUserIds().equals(Set.of(GUEST_ID)));
    }

    /** 이 컨텍스트의 클라이언트 이름을 가진 Pub/Sub 구독 연결만 서버에서 끊는다 */
    private void killOwnSubscriptionConnection() {
        int killed = stringRedisTemplate.execute((RedisCallback<Integer>) connection -> {
            int count = 0;
            for (RedisClientInfo info : connection.serverCommands().getClientList()) {
                if (NAMESPACE.equals(info.getName()) && isSubscribed(info)) {
                    kill(connection, info.getAddressPort());
                    count++;
                }
            }
            return count;
        });
        assertThat(killed).as("끊은 구독 연결 수").isEqualTo(1);
    }

    private static boolean isSubscribed(RedisClientInfo info) {
        String subscriptions = info.get("sub");
        return subscriptions != null && Integer.parseInt(subscriptions) > 0;
    }

    private static void kill(RedisConnection connection, String addressPort) {
        int separator = addressPort.lastIndexOf(':');
        connection.serverCommands().killClient(addressPort.substring(0, separator),
                Integer.parseInt(addressPort.substring(separator + 1)));
    }
}
