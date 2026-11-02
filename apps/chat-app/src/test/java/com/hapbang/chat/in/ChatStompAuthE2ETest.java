package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.hapbang.chat.app.ChatConnectionTicketStore;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.global.auth.GatewayUserHeaderFilter;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.in.websocket.ChatWebSocketSessionRegistry;
import com.hapbang.shared.member.domain.Role;

/**
 * 웹소켓 연결 티켓 인증을 실제 서버로 확인한다(설계 문서 6장, resync-frame.md "웹소켓 연결 티켓"). JWT는 Gateway만 검증하고,
 * chat-app은 Gateway 사용자 헤더로 인증한 REST 요청에 일회용 티켓을 주고 핸드셰이크에서 그 티켓을 쓴다.
 * <p>
 * 프론트가 받는 상태·프레임·종료 코드를 그대로 보려고 STOMP 클라이언트 없이 웹소켓에 프레임을 직접 쓴다. 발급 상한은 운영 값(10번)으로
 * 두고 창만 짧게 줄인다.
 */
@ChatE2ETest
class ChatStompAuthE2ETest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final String TICKET_PATH = E2ESession.TICKET_PATH;

    @DynamicPropertySource
    static void ticketLimit(DynamicPropertyRegistry registry) {
        registry.add("hapbang.chat.realtime.websocket.ticket-issue-limit", () -> "10");
        registry.add("hapbang.chat.realtime.websocket.ticket-issue-window", () -> "2s");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private LocalChatConnections localChatConnections;

    @Autowired
    private ChatWebSocketSessionRegistry chatWebSocketSessionRegistry;

    @Autowired
    private ChatConnectionTicketStore chatConnectionTicketStore;

    private RestTestClient client;
    private final List<RawConnection> connections = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void tearDown() {
        for (RawConnection connection : connections) {
            connection.closeQuietly();
        }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> localChatConnections.connectedUserIds().isEmpty());
    }

    @Test
    void 연결_티켓을_받아_연결하면_CONNECT가_통과하고_그_회원의_로컬_연결로_잡힌다() throws Exception {
        RawConnection connection = open(E2ESession.of(HOST_ID, Role.INFLUENCER).connectUrl(client, port));

        connection.send(connect());

        assertThat(connection.nextFrame()).startsWith("CONNECTED");
        assertThat(localChatConnections.sessionIdsOf(HOST_ID)).hasSize(1);
    }

    @Test
    void 티켓_발급은_로그인이_필요하고_채팅_역할만_받는다() {
        client.post().uri(TICKET_PATH).exchange().expectStatus().isUnauthorized();
        client.post().uri(TICKET_PATH).headers(E2ESession.of(USER_ID, Role.USER)::applyTo)
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void 같은_티켓으로_다시_연결하면_핸드셰이크가_401이다() throws Exception {
        String url = E2ESession.of(HOST_ID, Role.INFLUENCER).connectUrl(client, port);
        open(url);

        assertThat(handshakeFailure(url)).contains("[401]");
    }

    @Test
    void 만료된_티켓은_핸드셰이크가_401이다() throws Exception {
        String ticket = E2ESession.saveTicket(chatConnectionTicketStore, HOST_ID, Role.INFLUENCER, Duration.ofSeconds(1),
                Instant.now().plus(Duration.ofMinutes(15)));
        Thread.sleep(1500);

        assertThat(handshakeFailure(E2ESession.wsUrl(port, ticket))).contains("[401]");
    }

    @Test
    void 티켓이_없거나_모양이_틀리거나_모르는_티켓이면_핸드셰이크가_401이다() {
        String wsBase = "ws://localhost:" + port + E2ESession.WS_PATH;
        for (String url : List.of(wsBase, wsBase + "?ticket=", wsBase + "?ticket=short",
                E2ESession.wsUrl(port, "A".repeat(43)))) {
            assertThat(handshakeFailure(url)).as(url).contains("[401]");
        }
        assertThat(localChatConnections.connectedUserIds()).isEmpty();
    }

    // 핸드셰이크 요청의 사용자 헤더는 웹소켓 회원으로 쓰지 않는다
    @Test
    void 핸드셰이크에_사용자_헤더만_있고_티켓이_없으면_401이다() {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(GatewayUserHeaderFilter.USER_ID, String.valueOf(HOST_ID));
        headers.add(GatewayUserHeaderFilter.USER_ROLE, Role.ADMIN.name());

        assertThat(handshakeFailure("ws://localhost:" + port + E2ESession.WS_PATH, headers)).contains("[401]");
    }

    @Test
    void 허용하지_않은_출처는_403이고_티켓을_쓰지_않아_허용한_출처에서_같은_티켓으로_연결된다() throws Exception {
        String url = E2ESession.of(HOST_ID, Role.INFLUENCER).connectUrl(client, port);
        WebSocketHttpHeaders evil = new WebSocketHttpHeaders();
        evil.setOrigin("https://evil.example.com");

        assertThat(handshakeFailure(url, evil)).contains("[403]");

        WebSocketHttpHeaders allowed = new WebSocketHttpHeaders();
        allowed.setOrigin("http://localhost:5173");
        RawConnection connection = open(url, allowed);
        connection.send(connect());
        assertThat(connection.nextFrame()).startsWith("CONNECTED");
    }

    @Test
    void 연결_만료는_헤더의_exp를_따르되_발급_뒤_15분을_넘지_않고_헤더가_없거나_틀리면_15분이다() throws Exception {
        Instant before = Instant.now();
        Instant shortExp = before.plus(Duration.ofMinutes(5));
        Instant shortened = expiresAtOfConnection(String.valueOf(shortExp.getEpochSecond()));
        Instant capped = expiresAtOfConnection(String.valueOf(before.plus(Duration.ofDays(1)).getEpochSecond()));
        Instant missing = expiresAtOfConnection(null);
        Instant malformed = expiresAtOfConnection("tomorrow");
        Instant after = Instant.now();

        assertThat(shortened).isEqualTo(Instant.ofEpochSecond(shortExp.getEpochSecond()));
        for (Instant fallback : List.of(capped, missing, malformed)) {
            assertThat(fallback).isBetween(before.plus(Duration.ofMinutes(15)), after.plus(Duration.ofMinutes(15)));
        }
    }

    @Test
    void 연결_만료_시각이_되면_연결을_4401_TOKEN_EXPIRED로_닫는다() throws Exception {
        String tokenExp = String.valueOf(Instant.now().plusSeconds(2).getEpochSecond());
        RawConnection connection = open(E2ESession.wsUrl(port, issueTicket(HOST_ID, tokenExp)));
        connection.send(connect());
        assertThat(connection.nextFrame()).startsWith("CONNECTED");

        CloseStatus closeStatus = connection.closed().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(closeStatus.getCode()).isEqualTo(4401);
        assertThat(closeStatus.getReason()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void 같은_회원이_창_안에서_11번째로_받으면_429_TOO_MANY_TICKET_REQUESTS이고_창이_지나면_다시_받는다() {
        E2ESession admin = E2ESession.of(ADMIN_ID, Role.ADMIN);
        // 앞 창이 남아 있을 수 있어 새 창의 첫 발급이 될 때까지 기다린다
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> client.post().uri(TICKET_PATH)
                .headers(admin::applyTo).exchange().returnResult(String.class).getStatus().value() == 200);
        long started = System.nanoTime();
        for (int i = 2; i <= 10; i++) {
            admin.issueTicket(client);
        }
        String problem = client.post().uri(TICKET_PATH).headers(admin::applyTo).exchange()
                .expectStatus().isEqualTo(429)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(problem).contains("TOO_MANY_TICKET_REQUESTS");
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).untilAsserted(() -> admin.issueTicket(client));
    }

    @Test
    void 티켓_응답에_그_티켓으로_연결하면_닫히는_시각이_ISO_UTC로_있다() {
        Instant exp = Instant.now().plus(Duration.ofMinutes(5));

        String body = issueTicketBody(HOST_ID, String.valueOf(exp.getEpochSecond()));

        assertThat(body).contains("\"connectionExpiresAt\":\"" + Instant.ofEpochSecond(exp.getEpochSecond()) + "\"");
    }

    @Test
    void 열린_연결에_같은_회원의_새_티켓으로_재인증하면_그_연결에만_새_만료를_알리고_옛_만료에_닫지_않는다() throws Exception {
        RawConnection connection = connected(HOST_ID, Instant.now().plusSeconds(2));
        RawConnection other = connected(HOST_ID, Instant.now().plus(Duration.ofMinutes(5)));
        Instant renewed = Instant.ofEpochSecond(Instant.now().plus(Duration.ofMinutes(10)).getEpochSecond());

        connection.send(reauth(issueTicket(HOST_ID, String.valueOf(renewed.getEpochSecond()))));

        String frame = connection.nextFrame();
        assertThat(frame).startsWith("MESSAGE").contains("destination:/user/queue/chats/connection")
                .contains("\"type\":\"REAUTHENTICATED\"").contains("\"code\":null")
                .contains("\"connectionExpiresAt\":\"" + renewed + "\"");
        assertThat(localChatConnections.sessionIdsOf(HOST_ID)).map(chatWebSocketSessionRegistry::authenticatedUntil)
                .containsOnlyOnce(renewed);
        assertThat(other.frames().poll(500, TimeUnit.MILLISECONDS)).as("다른 연결에는 오지 않는다").isNull();
        assertThat(catchThrowable(() -> connection.closed().get(3, TimeUnit.SECONDS)))
                .as("옛 만료에 닫히지 않는다").isInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    void 재인증이_실패해도_연결을_두고_기존_만료에_4401로_닫는다() throws Exception {
        Instant expiresAt = Instant.ofEpochSecond(Instant.now().plusSeconds(3).getEpochSecond());
        String used = issueTicket(HOST_ID, String.valueOf(expiresAt.getEpochSecond()));
        RawConnection connection = open(E2ESession.wsUrl(port, used));
        connection.send(connect());
        assertThat(connection.nextFrame()).startsWith("CONNECTED");
        connection.send(subscribeConnectionQueue());
        String later = String.valueOf(Instant.now().plus(Duration.ofMinutes(10)).getEpochSecond());

        // 이미 쓴 티켓, 다른 회원의 티켓, 티켓 없음
        connection.send(reauth(used));
        assertThat(connection.nextFrame()).contains("\"type\":\"REAUTH_FAILED\"")
                .contains("\"code\":\"INVALID_TICKET\"").contains("\"connectionExpiresAt\":\"" + expiresAt + "\"");
        connection.send(reauth(issueTicket(GUEST_ID, later)));
        assertThat(connection.nextFrame()).contains("\"code\":\"MEMBER_MISMATCH\"");
        connection.send("SEND\ndestination:/pub/chats/reauth\n\n\0");
        assertThat(connection.nextFrame()).contains("\"code\":\"INVALID_TICKET\"");

        CloseStatus closeStatus = connection.closed().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(closeStatus.getCode()).isEqualTo(4401);
        assertThat(closeStatus.getReason()).isEqualTo("TOKEN_EXPIRED");
    }

    /** 이 회원으로 연결하고 CONNECTED를 받은 뒤 재인증 결과 큐를 구독한다 */
    private RawConnection connected(Long userId, Instant tokenExp) throws Exception {
        RawConnection connection = open(E2ESession.wsUrl(port,
                issueTicket(userId, String.valueOf(tokenExp.getEpochSecond()))));
        connection.send(connect());
        assertThat(connection.nextFrame()).startsWith("CONNECTED");
        connection.send(subscribeConnectionQueue());
        return connection;
    }

    private static String subscribeConnectionQueue() {
        return "SUBSCRIBE\nid:connection-0\ndestination:/user/queue/chats/connection\n\n\0";
    }

    private static String reauth(String ticket) {
        return "SEND\ndestination:/pub/chats/reauth\nticket:" + ticket + "\n\n\0";
    }

    private Instant expiresAtOfConnection(String tokenExpHeader) throws Exception {
        RawConnection connection = open(E2ESession.wsUrl(port, issueTicket(GUEST_ID, tokenExpHeader)));
        connection.send(connect());
        assertThat(connection.nextFrame()).startsWith("CONNECTED");
        String sessionId = localChatConnections.sessionIdsOf(GUEST_ID).iterator().next();
        Instant expiresAt = chatWebSocketSessionRegistry.authenticatedUntil(sessionId);
        connection.closeQuietly();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> localChatConnections.sessionIdsOf(GUEST_ID).isEmpty());
        return expiresAt;
    }

    /** Gateway가 access token의 exp로 넣는 헤더를 직접 실어 티켓을 받는다 */
    private String issueTicket(Long userId, String tokenExpHeader) {
        return issueTicketBody(userId, tokenExpHeader).replaceAll("(?s).*\"ticket\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private String issueTicketBody(Long userId, String tokenExpHeader) {
        String body = client.post().uri(TICKET_PATH)
                .headers(headers -> {
                    E2ESession.of(userId, Role.INFLUENCER).applyTo(headers);
                    if (tokenExpHeader != null) {
                        headers.set(GatewayUserHeaderFilter.USER_TOKEN_EXP, tokenExpHeader);
                    }
                })
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    private static String connect() {
        return "CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0";
    }

    private String handshakeFailure(String url) {
        return handshakeFailure(url, new WebSocketHttpHeaders());
    }

    /** 핸드셰이크가 실패해야 한다. 실패 원인 메시지(Tomcat 클라이언트는 응답 상태를 "[401]"처럼 담는다) */
    private String handshakeFailure(String url, WebSocketHttpHeaders headers) {
        Throwable failure = catchThrowable(() -> open(url, headers));
        assertThat(failure).as(url).isInstanceOf(ExecutionException.class);
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    private RawConnection open(String url) throws Exception {
        return open(url, new WebSocketHttpHeaders());
    }

    private RawConnection open(String url, WebSocketHttpHeaders headers) throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                frames.add(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closed.complete(status);
            }
        }, headers, URI.create(url)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        RawConnection connection = new RawConnection(session, frames, closed);
        connections.add(connection);
        return connection;
    }

    /** 웹소켓 연결 하나와 서버가 보낸 프레임·종료 상태 */
    private record RawConnection(WebSocketSession session, BlockingQueue<String> frames,
                                 CompletableFuture<CloseStatus> closed) {

        void send(String frame) throws Exception {
            session.sendMessage(new TextMessage(frame));
        }

        String nextFrame() throws InterruptedException {
            String frame = frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(frame).as("서버 프레임").isNotNull();
            return frame;
        }

        void closeQuietly() {
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (Exception ignored) {
                // 이미 닫히는 중
            }
        }
    }
}
