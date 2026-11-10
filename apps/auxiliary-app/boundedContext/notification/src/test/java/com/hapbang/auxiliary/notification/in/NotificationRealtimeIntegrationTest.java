package com.hapbang.auxiliary.notification.in;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
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

/**
 * 실시간 전달(ND-027·ND-059)과 연결 인증(ND-028·ND-060)을 실제 서버로 본다. test-spec NT-RT-01~05·08, NT-READ-05.
 * <ul>
 *     <li>프레임은 저장이 commit된 뒤에만 받는 사람 연결로 간다. 롤백·중복·밀려난 알림·읽음·전체 읽음은 보내지 않는다.</li>
 *     <li>Redis가 멈춰도 저장은 끝나고 되돌려지지 않으며, 재접속 뒤 REST 목록으로 맞출 수 있다.</li>
 *     <li>연결은 티켓으로만 연다. 클라이언트가 SEND로 다른 회원에게 프레임을 넣거나 다른 목적지를 구독할 수 없다.</li>
 * </ul>
 */
@NotificationRealtimeTest
// Redis를 멈춘 시험이 명령 응답을 오래 기다리지 않게 한다(기본 60초)
@TestPropertySource(properties = "spring.data.redis.timeout=1s")
class NotificationRealtimeIntegrationTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(40_000);
    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration QUIET = Duration.ofMillis(1500);
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

    @Test
    void 중복과_밀려난_알림은_프레임을_보내지_않고_중복_방지와_20개_보존은_그대로다() throws Exception {
        long a = member();
        for (int i = 0; i < 20; i++) {
            writer.save(draft(a, "new-" + i, 7L, "호스트", T0.plusSeconds(100 + i)));
        }
        RawStomp stomp = connect(a, Role.USER);

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

        RedisTestcontainersConfig.withRedisPaused(() -> {
            long started = System.nanoTime();
            result.set(writer.save(draft(a, "while-down", 7L, "호스트", T0)));
            elapsedMs.set(Duration.ofNanos(System.nanoTime() - started).toMillis());
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
        writer.save(draft(a, "after", 7L, "호스트", T0.plusSeconds(1)));
        assertThat(again.nextFrame(WAIT)).contains("\"targetId\":\"after\"");
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
        for (String destination : List.of("/user/" + b + "/queue/notifications", "/queue/notifications",
                "/topic/notifications")) {
            RawStomp stomp = RawStomp.open(wsUrl(ticket(a, Role.USER)));
            connections.add(stomp);
            stomp.send("CONNECT\naccept-version:1.2\nhost:localhost\n\n\0");
            assertThat(stomp.nextFrame(WAIT)).startsWith("CONNECTED");

            stomp.send("SUBSCRIBE\nid:x\ndestination:" + destination + "\n\n\0");

            assertThat(stomp.nextFrame(WAIT)).as(destination).startsWith("ERROR");
        }
        RawStomp victim = connect(b, Role.USER);
        writer.save(draft(b, "s-1", 7L, "호스트", T0));
        assertThat(victim.nextFrame(WAIT)).contains("\"targetId\":\"s-1\"");
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

    private long aliveCount(long userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notification.notification where receiver_user_id = ? and deleted_at is null",
                Long.class, userId);
    }
}
