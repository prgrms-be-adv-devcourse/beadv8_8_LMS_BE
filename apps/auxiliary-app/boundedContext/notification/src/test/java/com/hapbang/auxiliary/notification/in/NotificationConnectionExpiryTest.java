package com.hapbang.auxiliary.notification.in;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.CloseStatus;

import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.auxiliary.notification.app.NotificationConnectionTicketStore;
import com.hapbang.auxiliary.notification.app.NotificationUserSyncService;
import com.hapbang.auxiliary.notification.app.NotificationWriter;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicket;
import com.hapbang.auxiliary.notification.support.NotificationRealtimeTest;
import com.hapbang.auxiliary.notification.support.RawStomp;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.jayway.jsonpath.JsonPath;

/**
 * 티켓 유효 시간과 연결 만료는 다르다(ND-056·ND-057·ND-058, test-spec NT-RT-05·06).
 * <ul>
 *     <li>티켓은 짧게 산다(운영 30초, 이 시험은 1초). 지난 티켓으로는 연결하지 못한다.</li>
 *     <li>이미 열린 연결은 티켓 유효 시간과 관계없이 연결 만료(최대 15분)까지 산다.</li>
 *     <li>연결 만료가 되면 서버가 실제로 연결을 닫는다(종료 코드 4401).</li>
 *     <li>{@code X-User-Token-Exp}는 N4 전까지 믿을 수 없는 헤더다. 먼 미래 값을 넣어도 15분을 넘지 못한다.</li>
 * </ul>
 */
@NotificationRealtimeTest
@TestPropertySource(properties = "hapbang.notification.realtime.websocket.ticket-ttl=1s")
class NotificationConnectionExpiryTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(60_000);
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String QUEUE = "/user/queue/notifications";
    private static final String TICKET_PATH = "/api/v1/notifications/ws-tickets";
    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    private int port;

    @Autowired
    private NotificationWriter writer;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private NotificationConnectionTicketStore ticketStore;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

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

    @Test
    void 티켓_유효_시간이_지나면_그_티켓으로는_연결하지_못한다() throws Exception {
        String ticket = issue(member(), null).ticket();

        Thread.sleep(1500);

        assertThatThrownBy(() -> RawStomp.open(wsUrl(ticket))).hasStackTraceContaining("401");
    }

    @Test
    void 이미_열린_연결은_티켓_유효_시간이_지나도_계속_받는다() throws Exception {
        long a = member();
        RawStomp stomp = connect(issue(a, null).ticket(), a);

        Thread.sleep(1500);
        writer.save(draft(a, "s-1", 7L, "호스트", Instant.parse("2026-11-10T00:00:00Z")));

        assertThat(stomp.nextFrame(WAIT)).contains("\"targetId\":\"s-1\"");
        assertThat(stomp.isOpen()).isTrue();
    }

    @Test
    void 연결_만료가_되면_서버가_연결을_4401로_닫는다() throws Exception {
        long a = member();
        String ticket = saveTicket(a, Instant.now().plusSeconds(2));
        RawStomp stomp = connect(ticket, a);

        CloseStatus status = stomp.awaitClose(Duration.ofSeconds(6));

        assertThat(status).isNotNull();
        assertThat(status.getCode()).isEqualTo(4401);
        assertThat(stomp.isOpen()).isFalse();
        await().atMost(WAIT).until(() -> simpUserRegistry.getUser(String.valueOf(a)) == null);
    }

    @Test
    void 연결_만료가_이미_지난_티켓은_연결되더라도_곧바로_닫힌다() throws Exception {
        long a = member();
        String ticket = saveTicket(a, Instant.now().minusSeconds(1));

        RawStomp stomp = RawStomp.open(wsUrl(ticket));
        connections.add(stomp);

        assertThat(stomp.awaitClose(WAIT)).isNotNull();
    }

    @Test
    void 헤더로_먼_미래를_넣어도_연결_만료는_발급_시각_더하기_15분을_넘지_않는다() {
        Instant before = Instant.now();
        Instant forged = before.plus(Duration.ofDays(365));

        Issued issued = issue(member(), String.valueOf(forged.getEpochSecond()));

        assertThat(issued.connectionExpiresAt()).isBefore(Instant.now().plus(Duration.ofMinutes(15)).plusSeconds(1))
                .isAfter(before.plus(Duration.ofMinutes(15)).minusSeconds(1));
        assertThat(issued.expiresInSeconds()).isEqualTo(1);
    }

    @Test
    void 헤더가_15분보다_이른_exp를_주면_그_시각에_닫는다() throws Exception {
        long a = member();
        Instant exp = Instant.now().plusSeconds(3);
        Issued issued = issue(a, String.valueOf(exp.getEpochSecond()));
        RawStomp stomp = connect(issued.ticket(), a);

        assertThat(issued.connectionExpiresAt()).isEqualTo(Instant.ofEpochSecond(exp.getEpochSecond()));
        CloseStatus status = stomp.awaitClose(Duration.ofSeconds(7));
        assertThat(status).isNotNull();
        assertThat(status.getCode()).isEqualTo(4401);
    }

    @Test
    void 같은_회원이_60초_안에_11번째_티켓을_받으면_429다() {
        long a = member();
        for (int i = 0; i < 10; i++) {
            issue(a, null);
        }

        client.post().uri(TICKET_PATH).headers(h -> login(h, a)).exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                .expectBody().jsonPath("$.code").isEqualTo("TOO_MANY_REQUESTS");
    }

    // ---------------------------------------------------------------------------------------------

    private record Issued(String ticket, long expiresInSeconds, Instant connectionExpiresAt) {
    }

    private long member() {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.USER, UserStatus.ACTIVE, 0L));
        return id;
    }

    private Issued issue(long userId, String tokenExp) {
        String body = client.post().uri(TICKET_PATH).headers(h -> {
                    login(h, userId);
                    if (tokenExp != null) {
                        h.set("X-User-Token-Exp", tokenExp);
                    }
                }).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return new Issued(JsonPath.read(body, "$.data.ticket"),
                ((Number) JsonPath.read(body, "$.data.expiresInSeconds")).longValue(),
                Instant.parse(JsonPath.read(body, "$.data.connectionExpiresAt")));
    }

    /** 발급 API를 거치지 않고 연결 만료를 정한 티켓을 저장소에 넣는다 */
    private String saveTicket(long userId, Instant connectionExpiresAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ticketStore.save(ticket, new ConnectionTicket(userId, Role.USER, Instant.now(), connectionExpiresAt),
                Duration.ofSeconds(30));
        return ticket;
    }

    private RawStomp connect(String ticket, long userId) throws Exception {
        RawStomp stomp = RawStomp.connectAndSubscribe(wsUrl(ticket), QUEUE);
        connections.add(stomp);
        await().atMost(WAIT).until(() -> {
            SimpUser user = simpUserRegistry.getUser(String.valueOf(userId));
            return user != null && user.getSessions().stream().anyMatch(s -> !s.getSubscriptions().isEmpty());
        });
        return stomp;
    }

    private String wsUrl(String ticket) {
        return "ws://localhost:" + port + "/api/v1/notifications/ws?ticket=" + ticket;
    }

    private static void login(HttpHeaders headers, long userId) {
        headers.set(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId));
        headers.set(GatewayUserHeaderFilter.USER_ROLE, Role.USER.name());
    }
}
