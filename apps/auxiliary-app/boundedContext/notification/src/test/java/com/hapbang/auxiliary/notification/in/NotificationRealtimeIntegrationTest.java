package com.hapbang.auxiliary.notification.in;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;

import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.auxiliary.notification.app.DailyPayout;
import com.hapbang.auxiliary.notification.app.NotificationMaskingService;
import com.hapbang.auxiliary.notification.app.NotificationRealtimeRelay;
import com.hapbang.auxiliary.notification.app.NotificationUserSyncService;
import com.hapbang.auxiliary.notification.app.NotificationWriteResult;
import com.hapbang.auxiliary.notification.app.NotificationWriter;
import com.hapbang.auxiliary.notification.support.NotificationRealtimeTest;
import com.hapbang.auxiliary.notification.support.RawStomp;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 실시간 전달(ND-027·ND-059)과 연결 인증(ND-028·ND-060)을 실제 서버로 본다. test-spec NT-RT-01~05·08, NT-READ-05.
 * <ul>
 *     <li>프레임은 저장이 commit된 뒤에만 받는 사람 연결로 간다. 롤백·중복·밀려난 알림·읽음·전체 읽음은 보내지 않는다.</li>
 *     <li>Redis가 멈춰도 저장은 끝나고 되돌려지지 않으며, 재접속 뒤 REST 목록으로 맞출 수 있다.</li>
 *     <li>연결은 티켓으로만 연다. 클라이언트가 SEND로 다른 회원에게 프레임을 넣거나 다른 목적지를 구독할 수 없다.</li>
 * </ul>
 */
@NotificationRealtimeTest
// Redis를 멈춘 시험이 명령 응답을 오래 기다리지 않게 한다(기본 60초). 발행 실패 뒤 생략 시간도 줄인다(운영 기본 5초)
@TestPropertySource(properties = {"spring.data.redis.timeout=1s",
        "hapbang.notification.realtime.publish.failure-backoff=1s"})
class NotificationRealtimeIntegrationTest {

    /** NotificationConcurrencyTest(40_000)와 겹치지 않는 범위(같은 DB를 함께 쓴다) */
    private static final AtomicLong NEXT_ID = new AtomicLong(45_000);
    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration QUIET = Duration.ofMillis(1500);
    /** Redis를 멈춘 동안 발행 실패가 기록되기를 기다리는 상한(Redis 시간 제한 1초 + 전달 지연 여유) */
    private static final Duration PUBLISH_FAIL_WAIT = Duration.ofSeconds(10);
    /** 구독 거절 뒤 ERROR가 없을 때 연결 닫힘을 더 기다리는 시간 */
    private static final Duration REJECT_CLOSE_WAIT = Duration.ofSeconds(1);
    /** 앞선 저장이 없는 거절된 연결에 프레임이 오지 않는지 보는 시간 */
    private static final Duration NO_FRAME_WAIT = Duration.ofMillis(500);
    private static final Pattern TARGET_ID = Pattern.compile("\"targetId\":\"([^\"]+)\"");
    private static final String QUEUE = "/user/queue/notifications";
    private static final String WS_PATH = "/api/v1/notifications/ws";
    private static final String TICKET_PATH = "/api/v1/notifications/ws-tickets";

    @LocalServerPort
    private int port;

    @Autowired
    private NotificationWriter writer;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @Autowired
    private NotificationRealtimeRelay relay;

    @Autowired
    private NotificationMaskingService maskingService;

    @Autowired
    private MeterRegistry meterRegistry;

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

    // 전달

    @Test
    void 저장이_commit되면_받는_사람_연결로_알림_한_건과_안_읽은_수가_간다() throws Exception {
        long a = member();
        RawStomp stomp = connect(a, Role.USER);

        writer.save(draft(a, "s-1", 7L, "호스트", T0));

        String frame = stomp.nextFrame(WAIT);
        assertThat(frame).startsWith("MESSAGE").contains("destination:" + QUEUE)
                .contains("\"targetId\":\"s-1\"").contains("\"message\":\"호스트님이 강의 s-1에 반응했습니다.\"")
                .contains("\"unreadCount\":1");
        writer.save(draft(a, "s-2", 7L, "호스트", T0.plusSeconds(1)));
        assertThat(stomp.nextFrame(WAIT)).contains("\"targetId\":\"s-2\"").contains("\"unreadCount\":2");
    }

    @Test
    void 하루_묶음은_만들_때와_갱신할_때마다_갱신된_알림과_안_읽은_수를_보내고_중복은_보내지_않는다() throws Exception {
        long a = member();
        RawStomp stomp = connect(a, Role.USER);
        // KST 2026-11-10 09:00
        DailyPayout first = new DailyPayout(NEXT_ID.incrementAndGet(), a, 1_000L, T0);

        writer.addDailyPayout(first);
        assertThat(stomp.nextFrame(WAIT)).contains("\"kind\":\"PAYOUT_COMPLETED_DAILY\"")
                .contains("\"message\":\"11월 10일 강의 판매 수익 1,000 HP가 지급되었습니다. (1건)\"")
                .contains("\"unreadCount\":1");
        jdbcTemplate.update("update notification.notification set read_at = now() where receiver_user_id = ?", a);

        writer.addDailyPayout(new DailyPayout(NEXT_ID.incrementAndGet(), a, 2_000L, T0.plusSeconds(60)));
        assertThat(stomp.nextFrame(WAIT))
                .contains("\"message\":\"11월 10일 강의 판매 수익 3,000 HP가 지급되었습니다. (2건)\"")
                .contains("\"unreadCount\":1");

        writer.addDailyPayout(first);
        assertThat(stomp.nextFrame(QUIET)).isNull();
    }

    @Test
    void commit되기_전에는_프레임이_나가지_않는다() throws Exception {
        long a = member();
        RawStomp stomp = connect(a, Role.USER);
        AtomicReference<String> beforeCommit = new AtomicReference<>("not-checked");

        transactionTemplate.executeWithoutResult(status -> {
            writer.save(draft(a, "s-1", 7L, "호스트", T0));
            try {
                beforeCommit.set(stomp.nextFrame(QUIET));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(beforeCommit.get()).isNull();
        assertThat(stomp.nextFrame(WAIT)).contains("\"targetId\":\"s-1\"");
    }

    @Test
    void 롤백되면_저장도_프레임도_없다() throws Exception {
        long a = member();
        RawStomp stomp = connect(a, Role.USER);

        transactionTemplate.executeWithoutResult(status -> {
            writer.save(draft(a, "s-1", 7L, "호스트", T0));
            status.setRollbackOnly();
        });

        assertThat(stomp.nextFrame(QUIET)).isNull();
        assertThat(aliveCount(a)).isZero();
    }

    @Test
    void 다른_회원의_연결로는_가지_않는다() throws Exception {
        long a = member();
        long b = member();
        RawStomp stompA = connect(a, Role.USER);
        RawStomp stompB = connect(b, Role.INFLUENCER);

        writer.save(draft(a, "s-1", 7L, "호스트", T0));

        assertThat(stompA.nextFrame(WAIT)).contains("\"targetId\":\"s-1\"");
        assertThat(stompB.nextFrame(QUIET)).isNull();
    }

    @Test
    void 읽음과_전체_읽음은_프레임을_보내지_않는다() throws Exception {
        long a = member();
        RawStomp stomp = connect(a, Role.USER);
        writer.save(draft(a, "s-1", 7L, "호스트", T0));
        writer.save(draft(a, "s-2", 7L, "호스트", T0.plusSeconds(1)));
        assertThat(stomp.nextFrame(WAIT)).isNotNull();
        assertThat(stomp.nextFrame(WAIT)).isNotNull();
        long id = jdbcTemplate.queryForObject(
                "select id from notification.notification where receiver_user_id = ? and source_target_id = 's-1'",
                Long.class, a);

        client.patch().uri("/api/v1/notifications/{id}/read", id).headers(h -> login(h, a, Role.USER))
                .exchange().expectStatus().isOk();
        client.patch().uri("/api/v1/notifications/read-all").headers(h -> login(h, a, Role.USER))
                .exchange().expectStatus().isOk();

        assertThat(stomp.nextFrame(QUIET)).isNull();
    }

    /**
     * 실시간 전달은 저장 뒤 별도 스레드가 차례로 보낸다. 앞서 저장한 20건의 프레임을 모두 받은 뒤에 "조용함"을 본다. 비우지 않으면 느린
     * 환경(CI)에서 늦게 도착한 앞선 프레임을 중복·밀려난 알림의 프레임으로 잘못 본다(#849).
     */
    @Test
    void 중복과_밀려난_알림은_프레임을_보내지_않고_중복_방지와_20개_보존은_그대로다() throws Exception {
        long a = member();
        RawStomp stomp = connect(a, Role.USER);
        List<String> saved = IntStream.range(0, 20).mapToObj(i -> "new-" + i).toList();
        for (int i = 0; i < saved.size(); i++) {
            writer.save(draft(a, saved.get(i), 7L, "호스트", T0.plusSeconds(100 + i)));
        }
        Set<String> drained = new HashSet<>();
        for (int i = 0; i < saved.size(); i++) {
            String frame = stomp.nextFrame(WAIT);
            assertThat(frame).as("앞서 저장한 %d번째 프레임", i + 1).isNotNull();
            drained.add(targetIdOf(frame));
        }
        // 같은 프레임이 두 번 온 것이 아니라 앞서 저장한 20건이 하나씩 모두 왔는지 본다
        assertThat(drained).containsExactlyInAnyOrderElementsOf(saved);

        NotificationWriteResult duplicate = writer.save(draft(a, "new-0", 7L, "호스트", T0.plusSeconds(100)));
        NotificationWriteResult old = writer.save(draft(a, "old", 7L, "호스트", T0));

        assertThat(duplicate.outcome()).isEqualTo(NotificationWriteResult.Outcome.DUPLICATE);
        assertThat(old.outcome()).isEqualTo(NotificationWriteResult.Outcome.CREATED);
        assertThat(stomp.nextFrame(QUIET)).isNull();
        assertThat(aliveCount(a)).isEqualTo(20);
    }

    @Test
    void Redis가_멈춰도_저장은_끝나고_되돌려지지_않으며_재접속_뒤_목록으로_맞춘다() throws Exception {
        long a = member();
        RawStomp stomp = connect(a, Role.USER);
        AtomicReference<NotificationWriteResult> result = new AtomicReference<>();
        AtomicLong elapsedMs = new AtomicLong();

        double failedBefore = publishFailedCount();
        RedisTestcontainersConfig.withRedisPaused(() -> {
            long started = System.nanoTime();
            result.set(writer.save(draft(a, "while-down", 7L, "호스트", T0)));
            elapsedMs.set(Duration.ofNanos(System.nanoTime() - started).toMillis());
            // 발행은 저장 스레드 밖에서 늦게 일어날 수 있다. 멈춘 동안 발행이 실제로 실패한 뒤에 되살린다(되살린 뒤 시도하면 성공해 버린다, #849)
            await().atMost(PUBLISH_FAIL_WAIT).until(() -> publishFailedCount() > failedBefore);
        });

        assertThat(result.get().outcome()).isEqualTo(NotificationWriteResult.Outcome.CREATED);
        // 저장 경로는 Redis 응답을 기다리지 않는다(발행은 저장 스레드 밖에서 한다)
        assertThat(elapsedMs.get()).isLessThan(800);
        assertThat(aliveCount(a)).isEqualTo(1);
        stomp.closeQuietly();
        // 닫은 연결의 구독이 서버 목록에서 빠진 뒤 다시 연결한다(빠지기 전에 세면 새 구독을 알아보지 못한다)
        await().atMost(WAIT).until(() -> subscriptionCount(a) == 0);
        RawStomp again = connect(a, Role.USER);
        client.get().uri("/api/v1/notifications").headers(h -> login(h, a, Role.USER)).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data[0].targetId").isEqualTo("while-down");
        // 발행 실패 뒤 생략 시간(이 시험 1초)이 지나면 다시 보낸다. 생략한 프레임은 다시 보내지 않는다(REST로 맞춘다)
        Thread.sleep(1200);
        writer.save(draft(a, "after", 7L, "호스트", T0.plusSeconds(1)));
        String next = again.nextFrame(WAIT);
        assertThat(next).contains("\"targetId\":\"after\"").doesNotContain("while-down");
    }

    /**
     * ND-054: 파기가 진행 중(배타 잠금, 아직 commit 전)일 때 나가는 프레임이 옛 닉네임을 담으면, 파기가 끝난 뒤 FE에 옛 닉네임이 다시
     * 보인다. 전달은 언급 회원의 공유 잠금을 잡고 다시 읽은 값으로 보내야 한다.
     */
    @Test
    void 파기가_진행_중이면_프레임은_파기가_끝난_뒤의_닉네임으로_나간다() throws Exception {
        long a = member();
        long actor = 77_777L;
        RawStomp stomp = connect(a, Role.USER);
        writer.save(draft(a, "s-1", actor, "옛닉네임", T0));
        assertThat(stomp.nextFrame(WAIT)).contains("옛닉네임");
        long id = jdbcTemplate.queryForObject(
                "select id from notification.notification where receiver_user_id = ? and source_target_id = 's-1'",
                Long.class, a);
        CountDownLatch masking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread masker = new Thread(() -> transactionTemplate.executeWithoutResult(status -> {
            maskingService.mask(actor);
            masking.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        masker.start();
        assertThat(masking.await(5, TimeUnit.SECONDS)).isTrue();

        relay.publishAfterCommit(id, a);
        String whileMasking = stomp.nextFrame(QUIET);
        release.countDown();
        masker.join(10_000);

        assertThat(whileMasking).as("파기 commit 전에는 옛 닉네임 프레임이 나가면 안 된다").isNull();
        assertThat(stomp.nextFrame(WAIT)).contains("탈퇴회원#" + actor).doesNotContain("옛닉네임");
    }

    // 연결 인증

    @Test
    void 티켓이_없거나_틀리거나_이미_쓴_티켓이면_핸드셰이크가_401이다() throws Exception {
        long a = member();
        String base = "ws://localhost:" + port + WS_PATH;
        for (String url : List.of(base, base + "?ticket=", base + "?ticket=short", base + "?ticket=" + "A".repeat(43))) {
            assertThatThrownBy(() -> RawStomp.open(url)).as(url).hasStackTraceContaining("401");
        }
        String used = wsUrl(ticket(a, Role.USER));
        connections.add(RawStomp.open(used));

        assertThatThrownBy(() -> RawStomp.open(used)).hasStackTraceContaining("401");
    }

    /** 출처 검사가 티켓 사용보다 먼저 돈다. 거절된 출처는 403이고, 그 티켓은 소모되지 않아 허용 출처에서 그대로 쓸 수 있다 */
    @Test
    void 허용하지_않은_출처는_403이고_티켓을_소모하지_않는다() throws Exception {
        long a = member();
        String url = wsUrl(ticket(a, Role.USER));

        assertThatThrownBy(() -> RawStomp.open(url, "http://evil.example")).hasStackTraceContaining("403");

        RawStomp stomp = RawStomp.open(url);
        connections.add(stomp);
        assertThat(stomp.isOpen()).isTrue();
    }

    @Test
    void 티켓_발급은_로그인이_필요하고_관리자는_받지_못한다() {
        client.post().uri(TICKET_PATH).exchange().expectStatus().isUnauthorized();
        client.post().uri(TICKET_PATH).headers(h -> login(h, member(), Role.ADMIN)).exchange()
                .expectStatus().isForbidden();
        for (Role role : List.of(Role.USER, Role.INFLUENCER, Role.VERIFIED_INFLUENCER)) {
            client.post().uri(TICKET_PATH).headers(h -> login(h, member(), role)).exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.data.expiresInSeconds").isEqualTo(30);
        }
    }

    @Test
    void 클라이언트_SEND로_다른_회원에게_프레임을_넣을_수_없다() throws Exception {
        long a = member();
        long b = member();
        RawStomp attacker = connect(a, Role.USER);
        RawStomp victim = connect(b, Role.USER);

        for (String destination : List.of("/user/" + b + "/queue/notifications", "/queue/notifications",
                "/user/queue/notifications")) {
            try {
                attacker.send("SEND\ndestination:" + destination + "\ncontent-type:application/json\n\n"
                        + "{\"fake\":true}\0");
            } catch (Exception ignored) {
                // 앞선 SEND로 이미 닫혔을 수 있다
            }
        }

        assertThat(victim.nextFrame(QUIET)).isNull();
        String reply = attacker.nextFrame(WAIT);
        assertThat(reply == null || reply.startsWith("ERROR")).as(String.valueOf(reply)).isTrue();
    }

    @Test
    void 자기_알림_목적지_말고는_구독할_수_없다() throws Exception {
        long a = member();
        long b = member();
        List<RawStomp> rejected = new ArrayList<>();
        for (String destination : List.of("/user/" + b + "/queue/notifications", "/queue/notifications",
                "/topic/notifications")) {
            RawStomp stomp = RawStomp.open(wsUrl(ticket(a, Role.USER)));
            connections.add(stomp);
            rejected.add(stomp);
            stomp.send("CONNECT\naccept-version:1.2\nhost:localhost\n\n\0");
            assertThat(stomp.nextFrame(WAIT)).as(destination + " CONNECTED").startsWith("CONNECTED");

            stomp.send("SUBSCRIBE\nid:x\ndestination:" + destination + "\n\n\0");

            assertSubscriptionRejected(stomp, destination);
        }
        RawStomp victim = connect(b, Role.USER);
        writer.save(draft(b, "s-1", 7L, "호스트", T0));
        assertThat(victim.nextFrame(WAIT)).contains("\"targetId\":\"s-1\"");
        // 거절된 연결로는 다른 회원의 프레임이 오지 않는다(앞선 저장이 없는 연결이라 "안 옴" 판단에 늦은 프레임이 섞이지 않는다)
        for (RawStomp stomp : rejected) {
            assertThat(stomp.nextFrame(NO_FRAME_WAIT)).isNull();
        }
    }

    /**
     * 거절은 ERROR 프레임을 받는 것으로 본다. {@link RawStomp}는 닫히기 전에 받은 프레임을 큐에 남기므로, 서버가 ERROR를 보낸 뒤 닫았다면
     * 닫힘과 관계없이 ERROR를 읽는다. ERROR가 클라이언트에 도착하지 않은 채 서버가 PROTOCOL_ERROR(1002)로 닫은 경우만 닫힘으로 거절을
     * 본다(#849). 둘 다 아니면 어느 목적지에서 무엇을 받았는지 남긴다.
     */
    private static void assertSubscriptionRejected(RawStomp stomp, String destination) throws InterruptedException {
        String reply = stomp.nextFrame(WAIT);
        if (reply != null) {
            assertThat(reply).as("%s 구독에 받은 프레임", destination).startsWith("ERROR");
            return;
        }
        CloseStatus closed = stomp.awaitClose(REJECT_CLOSE_WAIT);
        assertThat(closed).as("%s 구독에 %d초 안에 ERROR도 연결 닫힘도 없음", destination,
                WAIT.plus(REJECT_CLOSE_WAIT).toSeconds()).isNotNull();
        // StompSubProtocolHandler.sendErrorMessage(WebSocketSession, Throwable)가 PROTOCOL_ERROR(1002)로 닫는다(spring-websocket 7.0.9, NR-028)
        assertThat(closed.getCode()).as("%s 구독 거절의 닫힘 코드", destination)
                .isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
    }

    /** STOMP MESSAGE 프레임 본문의 targetId */
    private static String targetIdOf(String frame) {
        Matcher matcher = TARGET_ID.matcher(frame);
        assertThat(matcher.find()).as("targetId가 없는 프레임: %s", frame).isTrue();
        return matcher.group(1);
    }

    // ---------------------------------------------------------------------------------------------

    private long member() {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.USER, UserStatus.ACTIVE, 0L));
        return id;
    }

    /** 연결하고 구독한 뒤, 서버가 그 구독을 등록할 때까지 기다린다(구독 전에 저장한 알림의 프레임을 놓치지 않게) */
    private RawStomp connect(long userId, Role role) throws Exception {
        int before = subscriptionCount(userId);
        RawStomp stomp = RawStomp.connectAndSubscribe(wsUrl(ticket(userId, role)), QUEUE);
        connections.add(stomp);
        await().atMost(WAIT).until(() -> subscriptionCount(userId) > before);
        return stomp;
    }

    private int subscriptionCount(long userId) {
        SimpUser user = simpUserRegistry.getUser(String.valueOf(userId));
        if (user == null) {
            return 0;
        }
        return user.getSessions().stream().mapToInt(session -> session.getSubscriptions().size()).sum();
    }

    private String ticket(long userId, Role role) {
        String ticket = client.post().uri(TICKET_PATH).headers(h -> login(h, userId, role)).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(ticket).isNotNull();
        return com.jayway.jsonpath.JsonPath.read(ticket, "$.data.ticket");
    }

    private String wsUrl(String ticket) {
        return "ws://localhost:" + port + WS_PATH + "?ticket=" + ticket;
    }

    private static void login(org.springframework.http.HttpHeaders headers, long userId, Role role) {
        headers.set(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId));
        headers.set(GatewayUserHeaderFilter.USER_ROLE, role.name());
    }

    /** Relay가 같은 MeterRegistry 빈에 올린 발행 실패 수. 이 컨텍스트의 MeterRegistry 빈이 Relay와 같아야 의미가 있다 */
    private double publishFailedCount() {
        Counter counter = meterRegistry.find(NotificationRealtimeRelay.DROPPED_METRIC).tag("reason", "publish_failed")
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long aliveCount(long userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notification.notification where receiver_user_id = ? and deleted_at is null",
                Long.class, userId);
    }
}
