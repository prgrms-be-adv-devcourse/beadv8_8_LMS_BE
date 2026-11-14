package com.hapbang.auxiliary.notification.in;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.draft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.CloseStatus;

import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.auxiliary.notification.app.NotificationUserSyncService;
import com.hapbang.auxiliary.notification.app.NotificationWriter;
import com.hapbang.auxiliary.notification.out.redis.NotificationRedisKeys;
import com.hapbang.auxiliary.notification.support.NotificationRealtimeTest;
import com.hapbang.auxiliary.notification.support.RawStomp;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실시간 전달 계측(ND-162)을 실제 서버로 본다. test-spec NT-OBS-07·08·09.
 * <ul>
 *     <li>계약: 지표를 더해도 FE가 받는 STOMP MESSAGE 프레임의 헤더와 본문, Redis 실시간 메시지 본문이 바뀌지 않는다.</li>
 *     <li>전달 한 건마다 Redis 발행 구간과 서버 내부 STOMP outbound 처리 구간이 한 번씩 기록된다.</li>
 *     <li>열린 연결 수 Gauge가 연결과 종료를 따라간다.</li>
 * </ul>
 * 지표 이름은 문자열로 고정해 이름이 바뀌면 이 시험이 깨진다.
 */
@NotificationRealtimeTest
class NotificationRealtimeMetricsTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(944_000);
    private static final Instant T0 = Instant.parse("2026-11-14T00:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String QUEUE = "/user/queue/notifications";
    private static final String PUBLISH_LATENCY = "hapbang.notification.realtime.publish.latency";
    private static final String SEND_LATENCY = "hapbang.notification.realtime.send.latency";
    private static final String SESSIONS = "hapbang.notification.realtime.sessions";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private NotificationWriter writer;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private NotificationRedisKeys redisKeys;

    private RestTestClient client;
    private final List<RawStomp> connections = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void tearDown() {
        connections.forEach(RawStomp::closeQuietly);
    }

    // 계약 (NT-OBS-08). 지표를 더하기 전 코드에서 먼저 통과시켜 고정한다

    @Test
    void FE가_받는_MESSAGE_프레임의_헤더는_정해진_다섯_개뿐이고_본문은_알림과_안_읽은_수다() throws Exception {
        long a = member();
        RawStomp stomp = connect(a);

        writer.save(draft(a, "c-1", 7L, "호스트", T0));

        String frame = stomp.nextFrame(WAIT);
        assertThat(frame).startsWith("MESSAGE\n");
        assertThat(headerNames(frame))
                .containsExactlyInAnyOrder("destination", "content-type", "subscription", "message-id",
                        "content-length");
        JsonNode body = JSON.readTree(body(frame));
        assertThat(fieldNames(body)).containsExactly("notification", "unreadCount");
        assertThat(body.get("notification").get("targetId").asString()).isEqualTo("c-1");
    }

    @Test
    void Redis_실시간_메시지_본문은_받는_사람과_프레임뿐이다() throws Exception {
        long a = member();
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        RedisConnection subscriber = redisConnectionFactory.getConnection();
        try {
            subscriber.subscribe((message, pattern) -> messages.add(
                            new String(message.getBody(), StandardCharsets.UTF_8)),
                    redisKeys.realtimeChannel().getBytes(StandardCharsets.UTF_8));

            writer.save(draft(a, "c-2", 7L, "호스트", T0));

            String raw = messages.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(raw).isNotNull();
            JsonNode message = JSON.readTree(raw);
            assertThat(fieldNames(message)).containsExactly("receiverUserId", "frame");
            assertThat(fieldNames(message.get("frame"))).containsExactly("notification", "unreadCount");
        } finally {
            subscriber.close();
        }
    }

    // 전달 구간 지표 (NT-OBS-07)

    @Test
    void 전달_한_건마다_Redis_발행_구간과_STOMP_outbound_처리_구간을_한_번씩_기록한다() throws Exception {
        long a = member();
        RawStomp stomp = connect(a);
        long publishBefore = timerCount(PUBLISH_LATENCY);
        long sendBefore = timerCount(SEND_LATENCY);

        writer.save(draft(a, "c-3", 7L, "호스트", T0));

        assertThat(stomp.nextFrame(WAIT)).startsWith("MESSAGE");
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(timerCount(PUBLISH_LATENCY)).isEqualTo(publishBefore + 1);
            assertThat(timerCount(SEND_LATENCY)).isEqualTo(sendBefore + 1);
        });
    }

    // 연결 수 (NT-OBS-09)

    @Test
    void 열린_연결_수는_연결하면_늘고_닫으면_준다() throws Exception {
        double before = sessions();
        RawStomp stomp = connect(member());

        await().atMost(WAIT).until(() -> sessions() == before + 1);

        stomp.closeQuietly();
        CloseStatus ignored = stomp.awaitClose(WAIT);
        await().atMost(WAIT).until(() -> sessions() == before);
    }

    private long timerCount(String name) {
        Timer timer = meterRegistry.find(name).timer();
        return timer == null ? 0 : timer.count();
    }

    private double sessions() {
        Gauge gauge = meterRegistry.find(SESSIONS).gauge();
        return gauge == null ? -1 : gauge.value();
    }

    private static Set<String> headerNames(String frame) {
        String head = frame.substring(0, frame.indexOf("\n\n"));
        return Arrays.stream(head.split("\n")).skip(1)
                .map(line -> line.substring(0, line.indexOf(':')))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String body(String frame) {
        String body = frame.substring(frame.indexOf("\n\n") + 2);
        return body.endsWith("\0") ? body.substring(0, body.length() - 1) : body;
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.propertyNames().stream().toList();
    }

    private long member() {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.USER, UserStatus.ACTIVE, 0L));
        return id;
    }

    private RawStomp connect(long userId) throws Exception {
        RawStomp stomp = RawStomp.connectAndSubscribe(wsUrl(ticket(userId)), QUEUE);
        connections.add(stomp);
        await().atMost(WAIT).until(() -> subscriptionCount(userId) > 0);
        return stomp;
    }

    private int subscriptionCount(long userId) {
        SimpUser user = simpUserRegistry.getUser(String.valueOf(userId));
        if (user == null) {
            return 0;
        }
        return user.getSessions().stream().mapToInt(session -> session.getSubscriptions().size()).sum();
    }

    private String ticket(long userId) {
        String ticket = client.post().uri("/api/v1/notifications/ws-tickets").headers(h -> {
                    h.set(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId));
                    h.set(GatewayUserHeaderFilter.USER_ROLE, Role.USER.name());
                }).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(ticket).isNotNull();
        return com.jayway.jsonpath.JsonPath.read(ticket, "$.data.ticket");
    }

    private String wsUrl(String ticket) {
        return "ws://localhost:" + port + "/api/v1/notifications/ws?ticket=" + ticket;
    }
}
