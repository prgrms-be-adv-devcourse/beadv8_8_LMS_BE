package com.hapbang.chat.in.websocket;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import com.hapbang.chat.app.ChatPresence;
import com.hapbang.chat.app.dto.ChatConnectionTicket;
import com.hapbang.chat.app.dto.ChatReauthResult;
import com.hapbang.chat.app.dto.ChatReauthResult.Outcome;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.chat.in.websocket.ChatWebSocketSessionRegistry.BindResult;

/**
 * 연결 목록과 연결별 인증 회원 결합(설계 문서 6장)을 검증한다. 실시간 전달·재검사·연결 제어가 쓰는 회원별 로컬 연결 목록
 * ({@link com.hapbang.chat.app.LocalChatConnections})은 회원을 결합한 연결만 가리킨다.
 */
class ChatWebSocketSessionRegistryTest {

    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
    private static final Instant LATER = NOW.plus(Duration.ofMinutes(15));

    private final TaskScheduler scheduler = idleScheduler();
    private final ChatWebSocketSessionRegistry registry = registry(scheduler, Clock.fixed(NOW, ZoneOffset.UTC));
    private final WebSocketHandler delegate = mock(WebSocketHandler.class);
    private final WebSocketHandler handler = registry.decorate(delegate);
    private ThreadPoolTaskScheduler realScheduler;
    private ThreadPoolTaskExecutor closer;

    @AfterEach
    void shutdown() {
        if (realScheduler != null) {
            realScheduler.shutdown();
        }
        if (closer != null) {
            closer.shutdown();
        }
    }

    @Test
    void 회원을_결합하지_않은_연결은_회원별_목록에_없고_결합해야_들어간다() throws Exception {
        WebSocketSession host = session("s-1");
        handler.afterConnectionEstablished(host);

        assertThat(registry.connectedUserIds()).isEmpty();
        assertThat(registry.isConnected("s-1")).isTrue();
        assertThat(registry.authenticatedUntil("s-1")).isNull();

        assertThat(registry.bind("s-1", HOST_ID, NOW, LATER)).isEqualTo(BindResult.BOUND);

        assertThat(registry.connectedUserIds()).containsExactly(HOST_ID);
        assertThat(registry.sessionIdsOf(HOST_ID)).containsExactly("s-1");
        assertThat(registry.authenticatedUntil("s-1")).isEqualTo(LATER);
        verify(scheduler).schedule(any(Runnable.class), eq(LATER));
    }

    @Test
    void 같은_회원의_연결이_하나라도_남아_있으면_로컬_연결이_있는_회원으로_본다() throws Exception {
        WebSocketSession hostDevice1 = connected("s-1", HOST_ID);
        WebSocketSession hostDevice2 = connected("s-2", HOST_ID);
        connected("s-3", GUEST_ID);

        assertThat(registry.connectedUserIds()).containsExactlyInAnyOrder(HOST_ID, GUEST_ID);
        assertThat(registry.connectedAmong(List.of(HOST_ID, ADMIN_ID))).containsExactly(HOST_ID);

        handler.afterConnectionClosed(hostDevice1, CloseStatus.NORMAL);
        assertThat(registry.connectedAmong(List.of(HOST_ID))).containsExactly(HOST_ID);

        handler.afterConnectionClosed(hostDevice2, CloseStatus.NORMAL);
        assertThat(registry.connectedAmong(List.of(HOST_ID, GUEST_ID))).containsExactly(GUEST_ID);
        assertThat(registry.connectedUserIds()).containsExactly(GUEST_ID);
        assertThat(registry.authenticatedUntil("s-1")).isNull();
    }

    @Test
    void 채팅_불가_닫기는_그_회원의_모든_로컬_연결을_CHAT_NOT_ALLOWED로_닫는다() throws Exception {
        WebSocketSession hostDevice1 = connected("s-1", HOST_ID);
        WebSocketSession hostDevice2 = connected("s-2", HOST_ID);
        WebSocketSession guest = connected("s-3", GUEST_ID);

        assertThat(registry.closeNotAllowed(HOST_ID)).isEqualTo(2);

        verify(hostDevice1).close(ChatWebSocketSessionRegistry.CHAT_NOT_ALLOWED);
        verify(hostDevice2).close(ChatWebSocketSessionRegistry.CHAT_NOT_ALLOWED);
        verify(guest, never()).close(any());
        assertThat(ChatWebSocketSessionRegistry.CHAT_NOT_ALLOWED.getCode()).isEqualTo(1008);
        assertThat(ChatWebSocketSessionRegistry.CHAT_NOT_ALLOWED.getReason()).isEqualTo("CHAT_NOT_ALLOWED");
    }

    @Test
    void 로그아웃_닫기는_그_회원의_모든_로컬_연결을_LOGGED_OUT으로_닫고_다른_회원과_인증_전_연결은_두며_반복해도_안전하다()
            throws Exception {
        WebSocketSession hostDevice1 = connected("s-1", HOST_ID);
        WebSocketSession hostDevice2 = connected("s-2", HOST_ID);
        WebSocketSession guest = connected("s-3", GUEST_ID);
        WebSocketSession anonymous = session("s-4");
        handler.afterConnectionEstablished(anonymous);

        assertThat(registry.closeLoggedOut(HOST_ID)).isEqualTo(2);
        handler.afterConnectionClosed(hostDevice1, ChatWebSocketSessionRegistry.LOGGED_OUT);
        handler.afterConnectionClosed(hostDevice2, ChatWebSocketSessionRegistry.LOGGED_OUT);
        assertThat(registry.closeLoggedOut(HOST_ID)).isZero();

        verify(hostDevice1).close(ChatWebSocketSessionRegistry.LOGGED_OUT);
        verify(hostDevice2).close(ChatWebSocketSessionRegistry.LOGGED_OUT);
        verify(guest, never()).close(any());
        verify(anonymous, never()).close(any());
        assertThat(ChatWebSocketSessionRegistry.LOGGED_OUT.getCode()).isEqualTo(1008);
        assertThat(ChatWebSocketSessionRegistry.LOGGED_OUT.getReason()).isEqualTo("LOGGED_OUT");
    }

    @Test
    void 핸드셰이크에서_쓴_연결_티켓이_있으면_연결이_열릴_때_회원_인증_시각_만료를_결합한다() throws Exception {
        WebSocketSession ticketed = session("s-1");
        when(ticketed.getAttributes()).thenReturn(new java.util.HashMap<>(java.util.Map.of(
                ChatConnectionTicketHandshakeInterceptor.CONNECTION_ATTRIBUTE,
                new ChatConnectionTicket(HOST_ID, Role.INFLUENCER, NOW, LATER))));
        WebSocketSession anonymous = session("s-2");

        handler.afterConnectionEstablished(ticketed);
        handler.afterConnectionEstablished(anonymous);

        assertThat(registry.userIdOf("s-1")).isEqualTo(HOST_ID);
        assertThat(registry.authenticatedUntil("s-1")).isEqualTo(LATER);
        assertThat(registry.userIdOf("s-2")).isNull();
        assertThat(registry.connectedUserIds()).containsExactly(HOST_ID);
        verify(scheduler).schedule(any(Runnable.class), eq(LATER));
    }

    @Test
    void 로그아웃_표식보다_먼저_인증한_연결만_LOGGED_OUT으로_닫고_뒤에_인증한_연결과_다른_회원은_둔다() throws Exception {
        Instant marker = NOW.plusSeconds(60);
        WebSocketSession before = session("s-1");
        WebSocketSession after = session("s-2");
        WebSocketSession other = session("s-3");
        handler.afterConnectionEstablished(before);
        handler.afterConnectionEstablished(after);
        handler.afterConnectionEstablished(other);
        registry.bind("s-1", HOST_ID, NOW, LATER);
        registry.bind("s-2", HOST_ID, marker.plusMillis(1), LATER);
        registry.bind("s-3", GUEST_ID, NOW, LATER);

        assertThat(registry.closeLoggedOutBefore(java.util.Map.of(HOST_ID, marker))).isEqualTo(1);
        assertThat(registry.closeLoggedOutBefore(java.util.Map.of())).isZero();

        verify(before).close(ChatWebSocketSessionRegistry.LOGGED_OUT);
        verify(after, never()).close(any());
        verify(other, never()).close(any());
    }

    @Test
    void 로컬_연결이_없는_회원을_닫아도_예외_없이_0을_돌려준다() {
        assertThat(registry.closeNotAllowed(UNKNOWN_ID)).isZero();
        assertThat(registry.closeLoggedOut(UNKNOWN_ID)).isZero();
        assertThat(registry.connectedAmong(List.of(UNKNOWN_ID))).isEmpty();
    }

    @Test
    void 한_연결에는_회원을_한_번만_결합한다_두_번째_결합으로_회원을_바꾸거나_만료를_늘리지_못한다() throws Exception {
        connected("s-1", HOST_ID);

        assertThat(registry.bind("s-1", GUEST_ID, NOW, LATER.plus(Duration.ofHours(1)))).isEqualTo(BindResult.ALREADY_BOUND);
        assertThat(registry.bind("s-1", HOST_ID, NOW, LATER.plus(Duration.ofHours(1)))).isEqualTo(BindResult.ALREADY_BOUND);

        assertThat(registry.authenticatedUntil("s-1")).isEqualTo(LATER);
        assertThat(registry.connectedUserIds()).containsExactly(HOST_ID);
        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void 없거나_닫힌_연결에는_결합하지_않는다() throws Exception {
        WebSocketSession closed = session("s-1");
        handler.afterConnectionEstablished(closed);
        when(closed.isOpen()).thenReturn(false);

        assertThat(registry.bind("unknown", HOST_ID, NOW, LATER)).isEqualTo(BindResult.CLOSED);
        assertThat(registry.bind(null, HOST_ID, NOW, LATER)).isEqualTo(BindResult.CLOSED);
        assertThat(registry.bind("s-1", HOST_ID, NOW, LATER)).isEqualTo(BindResult.CLOSED);
        assertThat(registry.connectedUserIds()).isEmpty();
    }

    @Test
    void 연결_시작_처리가_실패하면_목록에서_지워_결합할_수_없다() throws Exception {
        WebSocketSession host = session("s-1");
        doThrow(new IllegalStateException("시작 실패")).when(delegate).afterConnectionEstablished(host);

        assertThatThrownBy(() -> handler.afterConnectionEstablished(host)).isInstanceOf(IllegalStateException.class);

        assertThat(registry.isConnected("s-1")).isFalse();
        assertThat(registry.bind("s-1", HOST_ID, NOW, LATER)).isEqualTo(BindResult.CLOSED);
    }

    @Test
    void 연결이_닫히면_만료_예약을_취소한다() throws Exception {
        WebSocketSession host = connected("s-1", HOST_ID);
        assertThat(registry.expiryTimerCount()).isEqualTo(1);

        handler.afterConnectionClosed(host, CloseStatus.NORMAL);

        assertThat(registry.expiryTimerCount()).isZero();
    }

    @Test
    void 결합_도중_연결이_닫혀도_결합이_남지_않는다() throws Exception {
        WebSocketSession host = session("s-1");
        handler.afterConnectionEstablished(host);
        // 결합이 연결을 다시 보는 순간(결합을 넣은 뒤) 닫힘 처리가 끝난 상태를 만든다
        when(host.isOpen()).thenReturn(true).thenAnswer(invocation -> {
            handler.afterConnectionClosed(host, CloseStatus.GOING_AWAY);
            return false;
        });

        assertThat(registry.bind("s-1", HOST_ID, NOW, LATER)).isEqualTo(BindResult.CLOSED);

        assertThat(registry.connectedUserIds()).isEmpty();
        assertThat(registry.authenticatedUntil("s-1")).isNull();
        assertThat(registry.expiryTimerCount()).isZero();
    }

    @Test
    void 연결_만료_시각이_되면_그_연결만_4401_TOKEN_EXPIRED로_닫는다() throws Exception {
        realScheduler = new ThreadPoolTaskScheduler();
        realScheduler.initialize();
        ChatWebSocketSessionRegistry timed = registry(realScheduler, Clock.systemUTC());
        WebSocketHandler timedHandler = timed.decorate(mock(WebSocketHandler.class));
        WebSocketSession expiring = session("s-1");
        WebSocketSession other = session("s-2");
        timedHandler.afterConnectionEstablished(expiring);
        timedHandler.afterConnectionEstablished(other);

        timed.bind("s-1", HOST_ID, NOW, Instant.now().plusMillis(300));
        timed.bind("s-2", GUEST_ID, NOW, Instant.now().plus(Duration.ofMinutes(15)));

        verify(expiring, timeout(3000)).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
        verify(other, after(300).never()).close(any());
        assertThat(ChatWebSocketSessionRegistry.TOKEN_EXPIRED.getCode()).isEqualTo(4401);
        assertThat(ChatWebSocketSessionRegistry.TOKEN_EXPIRED.getReason()).isEqualTo("TOKEN_EXPIRED");
    }

    /**
     * 닫기는 Tomcat처럼 같은 스레드에서 닫힘 처리를 부르고, 그 안의 Redis 접속 정보 정리가 멈출 수 있다. 한 연결의 정리가 멈춰도
     * 만료 예약 스레드는 막히지 않아 다른 연결의 예약 종료와 주기 대조(예약이 빠진 연결)가 제때 돈다.
     */
    @Test
    void 한_연결의_닫힘_정리가_멈춰도_다른_연결의_만료_종료와_주기_대조는_제때_돈다() throws Exception {
        realScheduler = new DroppingScheduler(2);
        realScheduler.initialize();
        closer = new ThreadPoolTaskExecutor();
        closer.setCorePoolSize(2);
        closer.setMaxPoolSize(2);
        closer.setQueueCapacity(10);
        closer.initialize();
        CountDownLatch release = new CountDownLatch(1);
        ChatPresence presence = mock(ChatPresence.class);
        doAnswer(invocation -> release.await(10, TimeUnit.SECONDS)).when(presence).disconnected(eq(HOST_ID), any());
        ObjectProvider<ChatPresence> presenceProvider = absent();
        when(presenceProvider.getIfAvailable()).thenReturn(presence);
        ChatWebSocketSessionRegistry timed = new ChatWebSocketSessionRegistry(presenceProvider, absent(),
                realScheduler, closer, Clock.systemUTC(), Duration.ofMillis(200));
        WebSocketHandler timedHandler = timed.decorate(mock(WebSocketHandler.class));
        WebSocketSession blocked = closingSession("s-1", timedHandler);
        WebSocketSession scheduled = closingSession("s-2", timedHandler);
        WebSocketSession swept = closingSession("s-3", timedHandler);

        try {
            timed.bind("s-1", HOST_ID, NOW, Instant.now().plusMillis(100));
            timed.bind("s-2", GUEST_ID, NOW, Instant.now().plusMillis(400));
            // 세 번째 예약은 스케줄러가 버린다. 주기 대조만이 닫을 수 있다
            timed.bind("s-3", ADMIN_ID, NOW, Instant.now().plusMillis(300));

            verify(blocked, timeout(3000)).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
            verify(presence, timeout(3000)).disconnected(eq(HOST_ID), eq("s-1"));
            verify(scheduled, timeout(3000)).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
            verify(swept, timeout(3000)).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
            assertThat(timed.connectedUserIds()).doesNotContain(GUEST_ID, ADMIN_ID);
        } finally {
            release.countDown();
        }
    }

    @Test
    void 만료_예약이_빠져도_주기_대조가_만료가_지난_연결만_닫는다() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        ChatWebSocketSessionRegistry swept = registry(scheduler, clock);
        WebSocketHandler sweptHandler = swept.decorate(mock(WebSocketHandler.class));
        WebSocketSession expired = session("s-1");
        WebSocketSession valid = session("s-2");
        sweptHandler.afterConnectionEstablished(expired);
        sweptHandler.afterConnectionEstablished(valid);
        swept.bind("s-1", HOST_ID, NOW, NOW.plusSeconds(60));
        swept.bind("s-2", GUEST_ID, NOW, NOW.plusSeconds(600));

        assertThat(swept.closeExpired()).isZero();
        clock.now = NOW.plusSeconds(60);

        assertThat(swept.closeExpired()).isEqualTo(1);
        verify(expired).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
        verify(valid, never()).close(any());
        // 주기 대조는 생성할 때 예약한다
        verify(scheduler, atLeastOnce()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class),
                eq(Duration.ofSeconds(30)));
    }

    /**
     * 결합과 닫힘 처리가 동시에 돈다. 세 가지가 섞인다: 결합된 채 남는 연결, 결합 뒤 닫히는 연결, 닫힌 뒤에야 결합이 도는 연결.
     * 회원별 목록과 결합은 끝까지 열려 있는 연결만 가리켜야 하고, 그 연결이 모두 닫히면 회원 키·만료 예약도 사라져야 한다.
     */
    @Test
    void 한_회원의_여러_연결이_동시에_결합되고_닫혀도_열린_연결만_남는다() throws Exception {
        int sessionCount = 64;
        int rounds = 20;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < rounds; round++) {
                ChatWebSocketSessionRegistry roundRegistry = registry(idleScheduler(), Clock.systemUTC());
                WebSocketHandler roundHandler = roundRegistry.decorate(mock(WebSocketHandler.class));
                List<WebSocketSession> remaining = new ArrayList<>();
                List<Callable<Void>> tasks = new ArrayList<>();
                CountDownLatch start = new CountDownLatch(1);
                for (int i = 0; i < sessionCount; i++) {
                    AtomicBoolean open = new AtomicBoolean(true);
                    WebSocketSession session = switchableSession("s-" + i, open);
                    roundHandler.afterConnectionEstablished(session);
                    int kind = i % 3;
                    if (kind == 0) {
                        remaining.add(session);
                    }
                    tasks.add(() -> {
                        start.await();
                        if (kind == 2) {
                            // 닫힘 처리가 먼저 끝난 뒤 늦게 결합이 돈다
                            open.set(false);
                            roundHandler.afterConnectionClosed(session, CloseStatus.GOING_AWAY);
                            assertThat(roundRegistry.bind(session.getId(), HOST_ID, NOW, LATER))
                                    .isEqualTo(BindResult.CLOSED);
                            return null;
                        }
                        roundRegistry.bind(session.getId(), HOST_ID, NOW, LATER);
                        if (kind == 1) {
                            open.set(false);
                            roundHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
                        }
                        return null;
                    });
                }
                List<Future<Void>> futures = new ArrayList<>();
                for (Callable<Void> task : tasks) {
                    futures.add(executor.submit(task));
                }
                start.countDown();
                for (Future<Void> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }

                assertThat(roundRegistry.connectedUserIds()).containsExactly(HOST_ID);
                assertThat(roundRegistry.sessionIdsOf(HOST_ID)).hasSize(remaining.size());
                assertThat(roundRegistry.expiryTimerCount()).isEqualTo(remaining.size());

                for (WebSocketSession session : remaining) {
                    roundHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
                }
                assertThat(roundRegistry.connectedUserIds()).isEmpty();
                assertThat(roundRegistry.expiryTimerCount()).isZero();
                assertThat(roundRegistry.closeNotAllowed(HOST_ID)).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 재인증에_성공하면_만료를_새_값으로_바꾸고_옛_예약을_취소해_새_만료에_다시_예약한다() throws Exception {
        CapturingScheduler timers = new CapturingScheduler();
        MutableClock clock = new MutableClock(NOW);
        ChatWebSocketSessionRegistry reauth = registry(timers.scheduler(), clock);
        WebSocketHandler reauthHandler = reauth.decorate(mock(WebSocketHandler.class));
        WebSocketSession host = session("s-1");
        reauthHandler.afterConnectionEstablished(host);
        reauth.bind("s-1", HOST_ID, NOW, NOW.plusSeconds(60));
        Instant renewed = NOW.plus(Duration.ofMinutes(15));
        clock.now = NOW.plusSeconds(10);

        ChatReauthResult result = reauth.reauthenticate("s-1", HOST_ID, renewed);

        assertThat(result).isEqualTo(ChatReauthResult.reauthenticated(renewed));
        assertThat(reauth.authenticatedUntil("s-1")).isEqualTo(renewed);
        verify(timers.at(NOW.plusSeconds(60)).future()).cancel(false);
        assertThat(timers.at(renewed)).isNotNull();
        assertThat(reauth.expiryTimerCount()).isEqualTo(1);
        // 하트비트·주기 대조도 새 만료로 본다
        clock.now = NOW.plusSeconds(61);
        assertThat(reauth.closeExpired()).isZero();
        clock.now = renewed;
        assertThat(reauth.closeExpired()).isEqualTo(1);
        verify(host).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
    }

    @Test
    void 다른_회원의_티켓이거나_티켓의_만료가_지났으면_재인증하지_않고_기존_만료에_닫는다() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        ChatWebSocketSessionRegistry reauth = registry(scheduler, clock);
        WebSocketHandler reauthHandler = reauth.decorate(mock(WebSocketHandler.class));
        WebSocketSession host = session("s-1");
        reauthHandler.afterConnectionEstablished(host);
        reauth.bind("s-1", HOST_ID, NOW, NOW.plusSeconds(60));

        assertThat(reauth.reauthenticate("s-1", GUEST_ID, LATER))
                .isEqualTo(ChatReauthResult.failed(Outcome.MEMBER_MISMATCH, NOW.plusSeconds(60)));
        assertThat(reauth.reauthenticate("s-1", HOST_ID, NOW))
                .isEqualTo(ChatReauthResult.failed(Outcome.INVALID_TICKET, NOW.plusSeconds(60)));
        assertThat(reauth.reauthenticate("unknown", HOST_ID, LATER))
                .isEqualTo(ChatReauthResult.failed(Outcome.CONNECTION_CLOSING, null));

        assertThat(reauth.authenticatedUntil("s-1")).isEqualTo(NOW.plusSeconds(60));
        verify(host, never()).close(any());
        clock.now = NOW.plusSeconds(60);
        assertThat(reauth.closeExpired()).isEqualTo(1);
        verify(host).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
    }

    @Test
    void 만료_예약이_먼저_돌면_연결이_닫히고_그_뒤의_재인증은_거절된다() throws Exception {
        CapturingScheduler timers = new CapturingScheduler();
        MutableClock clock = new MutableClock(NOW);
        ChatWebSocketSessionRegistry reauth = registry(timers.scheduler(), clock);
        WebSocketHandler reauthHandler = reauth.decorate(mock(WebSocketHandler.class));
        WebSocketSession host = session("s-1");
        reauthHandler.afterConnectionEstablished(host);
        Instant expiresAt = NOW.plusSeconds(60);
        reauth.bind("s-1", HOST_ID, NOW, expiresAt);

        clock.now = expiresAt;
        timers.at(expiresAt).task().run();

        verify(host).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
        assertThat(reauth.reauthenticate("s-1", HOST_ID, LATER).outcome()).isEqualTo(Outcome.CONNECTION_CLOSING);
        assertThat(reauth.authenticatedUntil("s-1")).isEqualTo(expiresAt);
    }

    @Test
    void 재인증이_먼저면_옛_만료_예약이_늦게_돌아도_닫지_않고_새_만료에_닫는다() throws Exception {
        CapturingScheduler timers = new CapturingScheduler();
        MutableClock clock = new MutableClock(NOW);
        ChatWebSocketSessionRegistry reauth = registry(timers.scheduler(), clock);
        WebSocketHandler reauthHandler = reauth.decorate(mock(WebSocketHandler.class));
        WebSocketSession host = session("s-1");
        reauthHandler.afterConnectionEstablished(host);
        Instant expiresAt = NOW.plusSeconds(60);
        Instant renewed = NOW.plus(Duration.ofMinutes(15));
        reauth.bind("s-1", HOST_ID, NOW, expiresAt);
        clock.now = expiresAt.minusMillis(1);
        assertThat(reauth.reauthenticate("s-1", HOST_ID, renewed).succeeded()).isTrue();

        // 취소가 늦어 옛 예약이 이미 돌기 시작한 경우
        clock.now = expiresAt;
        timers.at(expiresAt).task().run();

        verify(host, never()).close(any());
        assertThat(reauth.expiryTimerCount()).isEqualTo(1);
        clock.now = renewed;
        timers.at(renewed).task().run();
        verify(host).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
    }

    /**
     * 만료 직전의 재인증이 확인을 마치고 멈춘 사이 옛 만료 예약이 닫기를 맡기는 순서다. 재인증이 성공했다고 답하고 연결은 곧바로
     * {@code 4401}로 닫히면 안 된다. 둘 중 하나만 이긴다: 재인증 성공이면 닫지 않고, 닫았으면 재인증은 {@code CONNECTION_CLOSING}이다.
     */
    @Test
    void 재인증이_확인_뒤_멈춘_사이_옛_만료_예약이_돌아도_재인증_성공과_만료_종료가_함께_일어나지_않는다() throws Exception {
        Instant expiresAt = NOW.plusSeconds(60);
        CountDownLatch reauthChecked = new CountDownLatch(1);
        CountDownLatch resumeReauth = new CountDownLatch(1);
        PausingClock clock = new PausingClock(NOW, "reauth", reauthChecked, resumeReauth);
        CapturingScheduler timers = new CapturingScheduler();
        ChatWebSocketSessionRegistry reauth = registry(timers.scheduler(), clock);
        WebSocketHandler reauthHandler = reauth.decorate(mock(WebSocketHandler.class));
        WebSocketSession host = session("s-1");
        reauthHandler.afterConnectionEstablished(host);
        reauth.bind("s-1", HOST_ID, NOW, expiresAt);
        clock.now = expiresAt.minusMillis(1);
        ExecutorService threads = Executors.newFixedThreadPool(2, task -> new Thread(task, "reauth"));
        ExecutorService timerThread = Executors.newSingleThreadExecutor(task -> new Thread(task, "timer"));
        try {
            // 재인증이 지금 시각(만료 1ms 전)을 읽은 채 멈춘다
            Future<ChatReauthResult> result = threads.submit(
                    () -> reauth.reauthenticate("s-1", HOST_ID, LATER.plusSeconds(60)));
            assertThat(reauthChecked.await(5, TimeUnit.SECONDS)).isTrue();
            // 그 사이 만료 시각이 되어 옛 예약이 돈다
            clock.now = expiresAt;
            Future<?> timer = timerThread.submit(timers.at(expiresAt).task());
            try {
                timer.get(500, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException blocked) {
                // 재인증이 끝날 때까지 기다리는 구현이다
            }
            resumeReauth.countDown();
            ChatReauthResult outcome = result.get(5, TimeUnit.SECONDS);
            timer.get(5, TimeUnit.SECONDS);

            if (outcome.succeeded()) {
                verify(host, never()).close(any());
                assertThat(reauth.authenticatedUntil("s-1")).isEqualTo(LATER.plusSeconds(60));
            } else {
                assertThat(outcome.outcome()).isEqualTo(Outcome.CONNECTION_CLOSING);
                verify(host).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
            }
        } finally {
            resumeReauth.countDown();
            threads.shutdownNow();
            timerThread.shutdownNow();
        }
    }

    @Test
    void 재인증해도_인증_시각은_처음_연결의_티켓_발급_시각이라_연결한_뒤의_로그아웃_표식이_닫는다() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        ChatWebSocketSessionRegistry reauth = registry(scheduler, clock);
        WebSocketHandler reauthHandler = reauth.decorate(mock(WebSocketHandler.class));
        WebSocketSession host = session("s-1");
        reauthHandler.afterConnectionEstablished(host);
        reauth.bind("s-1", HOST_ID, NOW, NOW.plusSeconds(60));
        Instant loggedOutAt = NOW.plusSeconds(20);
        clock.now = NOW.plusSeconds(30);
        assertThat(reauth.reauthenticate("s-1", HOST_ID, LATER).succeeded()).isTrue();

        assertThat(reauth.closeLoggedOutBefore(java.util.Map.of(HOST_ID, loggedOutAt))).isEqualTo(1);

        verify(host).close(ChatWebSocketSessionRegistry.LOGGED_OUT);
    }

    @Test
    void 로그아웃_표식으로_닫기를_맡긴_연결은_재인증하지_않는다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService closing = Executors.newSingleThreadExecutor();
        try {
            ChatWebSocketSessionRegistry reauth = new ChatWebSocketSessionRegistry(absent(), absent(), scheduler,
                    task -> closing.execute(() -> {
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        task.run();
                    }), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));
            WebSocketHandler reauthHandler = reauth.decorate(mock(WebSocketHandler.class));
            WebSocketSession host = session("s-1");
            reauthHandler.afterConnectionEstablished(host);
            reauth.bind("s-1", HOST_ID, NOW.minusSeconds(1), LATER);
            assertThat(reauth.closeLoggedOutBefore(java.util.Map.of(HOST_ID, NOW))).isEqualTo(1);

            assertThat(reauth.reauthenticate("s-1", HOST_ID, LATER.plusSeconds(60)))
                    .isEqualTo(ChatReauthResult.failed(Outcome.CONNECTION_CLOSING, LATER));

            release.countDown();
            verify(host, timeout(3000)).close(ChatWebSocketSessionRegistry.LOGGED_OUT);
        } finally {
            release.countDown();
            closing.shutdownNow();
        }
    }

    private WebSocketSession connected(String id, Long userId) throws Exception {
        WebSocketSession session = session(id);
        handler.afterConnectionEstablished(session);
        assertThat(registry.bind(id, userId, NOW, LATER)).isEqualTo(BindResult.BOUND);
        return session;
    }

    /** close가 Tomcat처럼 같은 스레드에서 닫힘 처리를 부르는 연결 */
    private static WebSocketSession closingSession(String id, WebSocketHandler handler) throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        WebSocketSession session = switchableSession(id, open);
        doAnswer(invocation -> {
            open.set(false);
            handler.afterConnectionClosed(session, invocation.getArgument(0));
            return null;
        }).when(session).close(any(CloseStatus.class));
        handler.afterConnectionEstablished(session);
        return session;
    }

    /** 앞의 {@code keep}개 일회 예약만 실제로 걸고 나머지는 버리는 스케줄러(만료 예약이 빠진 상황). 주기 예약은 그대로 건다 */
    private static final class DroppingScheduler extends ThreadPoolTaskScheduler {

        private final java.util.concurrent.atomic.AtomicInteger remaining;

        private DroppingScheduler(int keep) {
            this.remaining = new java.util.concurrent.atomic.AtomicInteger(keep);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            if (remaining.getAndDecrement() > 0) {
                return super.schedule(task, startTime);
            }
            return mock(ScheduledFuture.class);
        }
    }

    /** 일회 예약을 실행하지 않고 기억해 두는 스케줄러. 테스트가 예약을 원하는 순서로 직접 돌린다 */
    private static final class CapturingScheduler {

        private final List<Timer> timers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final TaskScheduler scheduler = idleScheduler();

        private CapturingScheduler() {
            when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
                ScheduledFuture<?> future = mock(ScheduledFuture.class);
                timers.add(new Timer(invocation.getArgument(0), invocation.getArgument(1), future));
                return future;
            });
        }

        TaskScheduler scheduler() {
            return scheduler;
        }

        /** 이 시각에 건 예약 */
        Timer at(Instant startTime) {
            return timers.stream().filter(timer -> timer.startTime().equals(startTime)).findFirst()
                    .orElseThrow(() -> new AssertionError("예약이 없습니다: " + startTime));
        }

        private record Timer(Runnable task, Instant startTime, ScheduledFuture<?> future) {
        }
    }

    private static ChatWebSocketSessionRegistry registry(TaskScheduler scheduler, Clock clock) {
        return new ChatWebSocketSessionRegistry(absent(), absent(), scheduler, clock, Duration.ofSeconds(30));
    }

    /** 예약은 받지만 실행하지 않는 스케줄러(만료 예약이 빠진 상황) */
    private static TaskScheduler idleScheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
        return scheduler;
    }

    private static WebSocketSession switchableSession(String id, AtomicBoolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        return session;
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        return mock(ObjectProvider.class);
    }

    /** 이름이 {@code pausedThread}인 스레드가 처음 시각을 읽으면, 그 순간의 시각을 돌려주기 전에 멈추는 시계 */
    private static final class PausingClock extends Clock {

        private final String pausedThread;
        private final CountDownLatch paused;
        private final CountDownLatch resume;
        private final AtomicBoolean pausedOnce = new AtomicBoolean();
        private volatile Instant now;

        private PausingClock(Instant now, String pausedThread, CountDownLatch paused, CountDownLatch resume) {
            this.now = now;
            this.pausedThread = pausedThread;
            this.paused = paused;
            this.resume = resume;
        }

        @Override
        public Instant instant() {
            Instant read = now;
            if (Thread.currentThread().getName().equals(pausedThread) && pausedOnce.compareAndSet(false, true)) {
                paused.countDown();
                try {
                    resume.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return read;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
