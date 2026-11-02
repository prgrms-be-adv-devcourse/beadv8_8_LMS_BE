package com.hapbang.chat.in.websocket;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import com.hapbang.chat.app.ChatPresence;
import com.hapbang.chat.app.ChatRoomTopicFacade;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.dto.ChatConnectionTicket;
import com.hapbang.chat.app.dto.ChatReauthResult;
import com.hapbang.chat.app.dto.ChatReauthResult.Outcome;

import lombok.extern.slf4j.Slf4j;

/**
 * 열린 채팅 웹소켓 연결 목록과 연결별 인증 회원(설계 문서 6장). 로그아웃·정지·강등·토큰 만료 때 서버가 연결을 닫기 위해 둔다.
 * 같은 서버의 연결만 안다(replica 1개).
 * <p>
 * <b>연결이 열릴 때 회원을 결합한다</b>: 핸드셰이크에서 쓴 연결 티켓({@link ChatConnectionTicketHandshakeInterceptor})의 회원·
 * 발급 시각(인증 시각)·연결 만료를 웹소켓 세션 속성에서 읽어 {@link #bind}로 결합한다. 그때 회원별 연결 목록·Redis 접속 정보에 넣고
 * 만료에 연결 종료를 예약한다. 티켓 없이 열린 연결은 결합하지 않고, CONNECT에서 거절된다.
 * <ul>
 *     <li>한 연결에는 회원을 한 번만 결합한다. CONNECT 프레임으로 회원을 바꾸거나 만료를 늘리지 못한다.</li>
 *     <li>만료: 티켓에 담은 연결 만료(access token {@code exp}, 상한 있음)에 그 연결을 {@link #TOKEN_EXPIRED}로 닫는다. 예약이 빠지거나
 *     늦은 경우를 위해 {@code expiry-sweep-interval}마다 만료가 지난 연결을 다시 찾아 닫는다.</li>
 *     <li>재인증({@link #reauthenticate}): 같은 회원의 새 티켓으로만 만료를 바꾼다. 만료 전에만 바꾸고 만료 종료를 다시 예약한다. 바꾸기
 *     전에 걸어 둔 예약이 늦게 돌아도 그때의 만료를 다시 보고 아직 남았으면 닫지 않는다. 인증 시각은 바꾸지 않는다.</li>
 *     <li>만료 예약·주기 대조 스레드는 닫기를 직접 하지 않고 상한이 있는 닫기 실행기에 맡긴다. 닫기는 Tomcat의 닫힘 처리
 *     ({@code afterConnectionClosed})를 같은 스레드에서 부르고 그 안의 Redis 접속 정보 정리가 느릴 수 있어, 예약 스레드에서
 *     하면 한 연결의 정리가 다른 연결의 만료 종료와 주기 대조를 모두 막는다. 같은 연결은 닫기를 기다리는 동안 한 번만 맡기고,
 *     닫기 대기열이 차서 맡기지 못한 연결은 다음 주기 대조가 다시 맡긴다.</li>
 *     <li>로그아웃 표식: 하트비트가 넘긴 회원별 로그아웃 시각보다 먼저 인증한 연결을 {@link #LOGGED_OUT}으로 닫는다
 *     ({@link #closeLoggedOutBefore}). 닫기는 같은 닫기 실행기에 맡긴다.</li>
 *     <li>연결이 닫히면 두 목록·만료 예약·방 topic 구독·Redis 접속 정보를 모두 지운다.</li>
 * </ul>
 * 실시간 전달·새 메시지 알림·연결 재검사는 회원별 연결 목록(인증된 연결만)으로 대상을 고른다.
 */
@Slf4j
@Component
public class ChatWebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory, LocalChatConnections,
        DisposableBean {

    /**
     * 회원이 로그아웃해서 닫음(회원 단위 로그아웃 이벤트, 설계 문서 Q8). 그 회원의 다른 기기 연결도 함께 닫힌다.
     * 프론트는 refresh를 한 번 시도해 성공하면 새 토큰으로 다시 연결하고, 실패하면 로그아웃한다(resync-frame.md)
     */
    public static final CloseStatus LOGGED_OUT = CloseStatus.POLICY_VIOLATION.withReason("LOGGED_OUT");
    /** 정지·탈퇴·강등으로 채팅할 수 없게 되어 닫음. 프론트는 재연결하지 않는다 */
    public static final CloseStatus CHAT_NOT_ALLOWED = CloseStatus.POLICY_VIOLATION.withReason("CHAT_NOT_ALLOWED");
    /**
     * 연결 만료(access token의 {@code exp}, 상한 있음)가 지나 닫음. 프론트는 refresh로 새 토큰을 받고 새 연결 티켓으로 다시 연결해
     * 이력을 재동기화한다(resync-frame.md). 4000번대는 애플리케이션이 정하는 종료 코드다(RFC 6455)
     */
    public static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4401, "TOKEN_EXPIRED");

    /**
     * 앱 전체 송신 대기열(clientOutboundChannel)이 넘쳐 이 연결로 가는 프레임을 버려서 닫음.
     * 정책 종료가 아니므로 프론트는 재연결하고 재동기화한다(resync-frame.md의 "그 밖의 종료")
     */
    public static final CloseStatus OUTBOUND_OVERFLOW = CloseStatus.SESSION_NOT_RELIABLE.withReason("OUTBOUND_OVERFLOW");
    /**
     * 앱 전체 수신 대기열(clientInboundChannel)이 넘쳐 이 연결이 보낸 프레임을 버려서 닫음.
     * 정책 종료가 아니므로 프론트는 재연결하고 재동기화한다. 버린 전송은 결과 미확인으로 같은 전송 ID로 다시 보낸다
     */
    public static final CloseStatus INBOUND_OVERFLOW = CloseStatus.SESSION_NOT_RELIABLE.withReason("INBOUND_OVERFLOW");

    /** {@link #bind}의 결과 */
    public enum BindResult {
        /** 회원을 결합했다 */
        BOUND,
        /** 이미 회원이 결합된 연결이다(두 번째 CONNECT) */
        ALREADY_BOUND,
        /** 연결이 없거나 이미 닫혔다 */
        CLOSED
    }

    /** 열린 연결(인증 전 포함). 연결 ID → 실제 소켓 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** 인증한 연결. 연결 ID → 결합한 회원·만료 */
    private final Map<String, SessionUser> users = new ConcurrentHashMap<>();
    /** 회원 ID → 그 회원의 인증한 연결 ID */
    private final Map<Long, Set<String>> sessionIdsByUserId = new ConcurrentHashMap<>();
    /** 연결 ID → 만료 종료 예약 */
    private final Map<String, ScheduledFuture<?>> expiryTimers = new ConcurrentHashMap<>();
    /** 만료·로그아웃 표식으로 닫기를 맡겨 두고 아직 끝나지 않은 연결 ID */
    private final Set<String> expiryClosing = ConcurrentHashMap.newKeySet();
    /** 실시간 전달을 끈 실행에는 없다(Redis 접속 정보) */
    private final ObjectProvider<ChatPresence> chatPresence;
    /** 방 topic 구독 목록 정리. 이 목록이 연결 목록을 쓰므로 생성 순환을 피해 지연해서 얻는다 */
    private final ObjectProvider<ChatRoomTopicFacade> chatRoomTopicFacade;
    private final TaskScheduler expiryScheduler;
    /** 만료 종료를 실제로 하는 실행기. 예약 스레드를 막지 않게 따로 둔다 */
    private final Executor expiryCloser;
    /** 이 목록이 만들어 직접 닫아야 하는 만료 스케줄러·닫기 실행기인지 */
    private final boolean ownsScheduler;
    private final Clock clock;

    /**
     * 만료 예약은 이 목록 전용 스레드 하나로 한다. 배치가 쓰는 공용 {@code taskScheduler}와 나눠, 배치가 밀려도 만료 종료가 늦지 않게
     * 한다. 닫기는 별도 실행기(스레드 4·대기열 10000)가 한다. 주기 대조 간격(30초)과 닫기 실행기 크기는 미확정 초기값(Q12)이다.
     * 기본값은 이 코드가 원본이고 application.yaml에 두지 않는다.
     */
    @Autowired
    public ChatWebSocketSessionRegistry(ObjectProvider<ChatPresence> chatPresence,
            ObjectProvider<ChatRoomTopicFacade> chatRoomTopicFacade,
            @Value("${hapbang.chat.realtime.websocket.expiry-sweep-interval:30s}") Duration expirySweepInterval,
            @Value("${hapbang.chat.realtime.websocket.expiry-close-workers:4}") int expiryCloseWorkers,
            @Value("${hapbang.chat.realtime.websocket.expiry-close-queue-capacity:10000}") int expiryCloseQueueCapacity) {
        this(chatPresence, chatRoomTopicFacade, expiryScheduler(),
                expiryCloser(expiryCloseWorkers, expiryCloseQueueCapacity), true, Clock.systemUTC(),
                expirySweepInterval);
    }

    /** 만료 스케줄러·닫기 실행기·시계를 직접 넘긴다(테스트). 넘긴 것은 닫지 않는다 */
    ChatWebSocketSessionRegistry(ObjectProvider<ChatPresence> chatPresence,
            ObjectProvider<ChatRoomTopicFacade> chatRoomTopicFacade, TaskScheduler expiryScheduler,
            Executor expiryCloser, Clock clock, Duration expirySweepInterval) {
        this(chatPresence, chatRoomTopicFacade, expiryScheduler, expiryCloser, false, clock, expirySweepInterval);
    }

    /** 만료 닫기를 부른 스레드에서 바로 한다(테스트) */
    ChatWebSocketSessionRegistry(ObjectProvider<ChatPresence> chatPresence,
            ObjectProvider<ChatRoomTopicFacade> chatRoomTopicFacade, TaskScheduler expiryScheduler, Clock clock,
            Duration expirySweepInterval) {
        this(chatPresence, chatRoomTopicFacade, expiryScheduler, Runnable::run, clock, expirySweepInterval);
    }

    private ChatWebSocketSessionRegistry(ObjectProvider<ChatPresence> chatPresence,
            ObjectProvider<ChatRoomTopicFacade> chatRoomTopicFacade, TaskScheduler expiryScheduler,
            Executor expiryCloser, boolean ownsScheduler, Clock clock, Duration expirySweepInterval) {
        Assert.isTrue(expirySweepInterval.isPositive(),
                "hapbang.chat.realtime.websocket.expiry-sweep-interval은 0보다 커야 합니다.");
        this.chatPresence = chatPresence;
        this.chatRoomTopicFacade = chatRoomTopicFacade;
        this.expiryScheduler = expiryScheduler;
        this.expiryCloser = expiryCloser;
        this.ownsScheduler = ownsScheduler;
        this.clock = clock;
        expiryScheduler.scheduleAtFixedRate(this::sweepExpired, clock.instant().plus(expirySweepInterval),
                expirySweepInterval);
    }

    private static ThreadPoolTaskScheduler expiryScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-ws-expiry-");
        // 연결이 닫히며 취소한 예약을 바로 지운다(만료까지 큐에 남지 않게)
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    private static ThreadPoolTaskExecutor expiryCloser(int workers, int queueCapacity) {
        Assert.isTrue(workers > 0, "hapbang.chat.realtime.websocket.expiry-close-workers는 1 이상이어야 합니다.");
        Assert.isTrue(queueCapacity > 0,
                "hapbang.chat.realtime.websocket.expiry-close-queue-capacity는 1 이상이어야 합니다.");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-ws-expiry-close-");
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }

    @Override
    public void destroy() {
        if (!ownsScheduler) {
            return;
        }
        if (expiryScheduler instanceof ThreadPoolTaskScheduler scheduler) {
            scheduler.shutdown();
        }
        if (expiryCloser instanceof ThreadPoolTaskExecutor executor) {
            executor.shutdown();
        }
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                register(session);
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception exception) {
                    // Spring이 오류로 닫으면 afterConnectionClosed도 오지만, 남지 않도록 먼저 지운다
                    unregister(session);
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                unregister(session);
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /**
     * 연결 티켓의 회원·인증 시각·만료를 이 연결에 결합한다. 회원별 연결 목록과 Redis 접속 정보에 넣고 {@code expiresAt}에 종료를
     * 예약한다. 연결이 열릴 때(STOMP 프레임보다 먼저) 결합하므로 CONNECT 전에 커밋된 연결 제어 작업도 이 연결을 닫는다.
     * <p>
     * 닫힘 처리와 겹치면: 닫힘 처리는 연결 목록에서 먼저 지운 뒤 결합을 읽고, 결합은 결합을 넣은 뒤 연결 목록을 다시 본다. 둘 중
     * 하나는 반드시 상대를 보므로 닫힌 연결의 결합이 남지 않는다.
     */
    public BindResult bind(String sessionId, Long userId, Instant authenticatedAt, Instant expiresAt) {
        Assert.notNull(userId, "userId가 필요합니다.");
        Assert.notNull(authenticatedAt, "authenticatedAt이 필요합니다.");
        Assert.notNull(expiresAt, "expiresAt이 필요합니다.");
        WebSocketSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return BindResult.CLOSED;
        }
        AtomicReference<BindResult> result = new AtomicReference<>();
        sessionIdsByUserId.compute(userId, (key, ids) -> {
            if (users.putIfAbsent(sessionId, new SessionUser(userId, authenticatedAt, new AtomicReference<>(expiresAt),
                    new AtomicBoolean())) != null) {
                result.set(BindResult.ALREADY_BOUND);
                return ids;
            }
            Set<String> next = ids == null ? ConcurrentHashMap.newKeySet() : ids;
            next.add(sessionId);
            result.set(BindResult.BOUND);
            return next;
        });
        if (result.get() != BindResult.BOUND) {
            return result.get();
        }
        if (sessions.get(sessionId) != session || !session.isOpen()) {
            unbind(sessionId);
            return BindResult.CLOSED;
        }
        scheduleExpiry(sessionId, users.get(sessionId), expiresAt);
        ChatPresence presence = chatPresence.getIfAvailable();
        if (presence != null) {
            try {
                presence.connected(userId, sessionId);
            } catch (RuntimeException exception) {
                // 다음 하트비트가 다시 넣는다. 그 사이 새 메시지 알림을 놓치면 FE 주기 대조가 메운다
                log.warn("접속 정보 기록 실패 userId={}, sessionId={}", userId, sessionId, exception);
            }
        }
        return BindResult.BOUND;
    }

    /**
     * 이 연결의 첫 CONNECT·STOMP를 기록한다. 같은 연결의 두 번째 CONNECT를 거절하는 데 쓴다.
     *
     * @return 처음이면 true. 결합하지 않은 연결이거나 이미 CONNECT를 받았으면 false
     */
    public boolean markStompConnected(String sessionId) {
        SessionUser user = sessionId == null ? null : users.get(sessionId);
        return user != null && user.stompConnected().compareAndSet(false, true);
    }

    /** 이 연결에 결합한 회원 ID. 결합하지 않았거나 닫힌 연결이면 null */
    public Long userIdOf(String sessionId) {
        SessionUser user = sessionId == null ? null : users.get(sessionId);
        return user == null ? null : user.userId();
    }

    /** 이 연결에 결합한 회원의 만료 시각. 인증 전이거나 닫힌 연결이면 null */
    @Override
    public Instant authenticatedUntil(String sessionId) {
        SessionUser user = sessionId == null ? null : users.get(sessionId);
        return user == null ? null : user.expiresAt().get();
    }

    /**
     * 열린 연결의 만료를 같은 회원의 새 티켓 값으로 바꾸고 만료 종료를 다시 예약한다(설계 문서 6장 "재인증").
     * <ul>
     *     <li>결합하지 않았거나 닫힌 연결, 닫기를 맡겨 둔 연결(만료·로그아웃 표식), 이미 만료가 지난 연결은 바꾸지 않는다.</li>
     *     <li>다른 회원의 티켓이면 바꾸지 않는다.</li>
     *     <li>만료 종료와의 경합: 만료 교체와 닫기 맡기기({@link #markClosing})는 연결마다 같은 잠금 안에서 한다. 재인증은 잠금 안에서
     *     지금 시각을 읽고 "닫기를 맡기지 않았고 만료가 남았을 때만" 바꾼다. 만료 종료는 잠금 안에서 그때의 만료를 다시 보고 "지났을
     *     때만" 닫기를 맡긴다. 그래서 닫기가 먼저 맡겨졌으면 재인증은 언제나 {@code CONNECTION_CLOSING}이고, 재인증이 먼저 끝났으면 옛
     *     예약·주기 대조·프레임 검사는 그 연결을 만료로 닫지 않는다.</li>
     *     <li>인증 시각은 처음 연결한 티켓의 발급 시각 그대로다. 연결한 뒤의 로그아웃 표식은 재인증한 연결도 닫는다.</li>
     * </ul>
     */
    @Override
    public ChatReauthResult reauthenticate(String sessionId, Long userId, Instant connectionExpiresAt) {
        Assert.notNull(userId, "userId가 필요합니다.");
        Assert.notNull(connectionExpiresAt, "connectionExpiresAt이 필요합니다.");
        SessionUser user = sessionId == null ? null : users.get(sessionId);
        if (user == null || !isConnected(sessionId)) {
            return ChatReauthResult.failed(Outcome.CONNECTION_CLOSING, null);
        }
        if (!user.userId().equals(userId)) {
            return ChatReauthResult.failed(Outcome.MEMBER_MISMATCH, user.expiresAt().get());
        }
        synchronized (user) {
            Instant current = user.expiresAt().get();
            Instant now = clock.instant();
            if (expiryClosing.contains(sessionId) || !current.isAfter(now)) {
                return ChatReauthResult.failed(Outcome.CONNECTION_CLOSING, current);
            }
            if (!connectionExpiresAt.isAfter(now)) {
                return ChatReauthResult.failed(Outcome.INVALID_TICKET, current);
            }
            user.expiresAt().set(connectionExpiresAt);
        }
        scheduleExpiry(sessionId, user, connectionExpiresAt);
        return ChatReauthResult.reauthenticated(connectionExpiresAt);
    }

    /**
     * 만료가 지난 연결을 바로 {@link #TOKEN_EXPIRED}로 닫는다(프레임 검사용). 만료 확인과 닫기 표시는 재인증과 같은 잠금 안에서 한다.
     *
     * @return 만료가 지나 닫았거나 이미 닫는 중이면 true. 그 사이 재인증으로 만료가 늦춰졌으면 false(연결을 그대로 둔다)
     */
    public boolean closeIfExpired(String sessionId) {
        SessionUser user = sessionId == null ? null : users.get(sessionId);
        if (user == null) {
            return true;
        }
        ClosingMark mark = markClosing(user, sessionId, TOKEN_EXPIRED);
        if (mark == ClosingMark.NOT_EXPIRED) {
            return false;
        }
        if (mark == ClosingMark.MARKED) {
            try {
                closeSession(sessionId, TOKEN_EXPIRED);
            } finally {
                expiryClosing.remove(sessionId);
            }
        }
        return true;
    }

    /** 이 회원의 모든 연결을 닫는다 */
    public int closeByUserId(Long userId, CloseStatus status) {
        int closed = 0;
        for (String sessionId : sessionIdsOf(userId)) {
            if (closeSession(sessionId, status)) {
                closed++;
            }
        }
        return closed;
    }

    /**
     * 이 연결 하나를 닫는다. STOMP 세션 ID는 WebSocket 세션 ID와 같다.
     *
     * @return 닫기를 요청했으면 true. 이미 목록에 없거나 닫기에 실패했으면 false
     */
    public boolean closeSession(String sessionId, CloseStatus status) {
        WebSocketSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        try {
            session.close(status);
            return true;
        } catch (IOException | IllegalStateException exception) {
            // 이미 닫히는 중이거나 전송과 겹친 경우. 닫힘 처리는 afterConnectionClosed가 한다
            log.warn("채팅 웹소켓 연결 닫기 실패 sessionId={}", sessionId, exception);
            return false;
        }
    }

    /**
     * 만료가 지난 인증 연결을 모두 {@link #TOKEN_EXPIRED}로 닫도록 닫기 실행기에 맡긴다. 만료 예약이 빠지거나 늦은 연결을 위한
     * 주기 대조다. 이 메서드는 닫기를 기다리지 않는다.
     *
     * @return 이번에 새로 닫기를 맡긴 연결 수(이미 닫기를 기다리는 연결은 세지 않는다)
     */
    @Override
    public int closeExpired() {
        Instant now = clock.instant();
        int requested = 0;
        for (Map.Entry<String, SessionUser> entry : users.entrySet()) {
            if (!entry.getValue().expiresAt().get().isAfter(now) && closeLater(entry.getKey(), TOKEN_EXPIRED)) {
                requested++;
            }
        }
        return requested;
    }

    /**
     * 회원별 로그아웃 시각보다 먼저 인증한(티켓을 발급받은) 연결을 {@link #LOGGED_OUT}으로 닫도록 닫기 실행기에 맡긴다. 로그아웃 뒤에
     * 새 티켓으로 맺은 연결은 그대로 둔다. 이 메서드는 닫기를 기다리지 않는다.
     */
    @Override
    public int closeLoggedOutBefore(Map<Long, Instant> loggedOutAt) {
        if (loggedOutAt.isEmpty()) {
            return 0;
        }
        int requested = 0;
        for (Map.Entry<String, SessionUser> entry : users.entrySet()) {
            Instant marker = loggedOutAt.get(entry.getValue().userId());
            if (marker != null && entry.getValue().authenticatedAt().isBefore(marker)
                    && closeLater(entry.getKey(), LOGGED_OUT)) {
                requested++;
            }
        }
        return requested;
    }

    @Override
    public Set<Long> connectedUserIds() {
        return Set.copyOf(sessionIdsByUserId.keySet());
    }

    @Override
    public Set<Long> connectedAmong(Collection<Long> userIds) {
        Set<Long> connected = new HashSet<>();
        for (Long userId : userIds) {
            if (userId != null && sessionIdsByUserId.containsKey(userId)) {
                connected.add(userId);
            }
        }
        return connected;
    }

    @Override
    public Set<String> sessionIdsOf(Long userId) {
        Set<String> ids = userId == null ? null : sessionIdsByUserId.get(userId);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    @Override
    public boolean isConnected(String sessionId) {
        WebSocketSession session = sessionId == null ? null : sessions.get(sessionId);
        return session != null && session.isOpen();
    }

    @Override
    public int closeNotAllowed(Long userId) {
        return closeByUserId(userId, CHAT_NOT_ALLOWED);
    }

    @Override
    public int closeLoggedOut(Long userId) {
        return closeByUserId(userId, LOGGED_OUT);
    }

    /**
     * 연결을 넣고, 핸드셰이크에서 쓴 연결 티켓이 있으면 회원을 결합한다. 그 사이 이미 닫힌 연결은 넣지 않는다(닫힘 처리가 먼저
     * 끝났으면 다시 지울 기회가 없다).
     */
    private void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
        if (!session.isOpen()) {
            sessions.remove(session.getId(), session);
            return;
        }
        if (session.getAttributes().get(ChatConnectionTicketHandshakeInterceptor.CONNECTION_ATTRIBUTE)
                instanceof ChatConnectionTicket connection) {
            bind(session.getId(), connection.userId(), connection.issuedAt(), connection.tokenExpiresAt());
        }
    }

    private void unregister(WebSocketSession session) {
        ChatRoomTopicFacade topics = chatRoomTopicFacade.getIfAvailable();
        if (topics != null) {
            topics.sessionClosed(session.getId());
        }
        sessions.remove(session.getId(), session);
        unbind(session.getId());
    }

    /** 결합을 지운다. 여러 번 불러도 한 번만 지우고, 지운 쪽만 만료 예약을 취소하고 접속 정보를 지운다 */
    private void unbind(String sessionId) {
        SessionUser user = users.get(sessionId);
        if (user == null) {
            return;
        }
        AtomicReference<SessionUser> removed = new AtomicReference<>();
        sessionIdsByUserId.compute(user.userId(), (key, ids) -> {
            if (users.remove(sessionId, user)) {
                removed.set(user);
            }
            if (ids == null) {
                return null;
            }
            ids.remove(sessionId);
            return ids.isEmpty() ? null : ids;
        });
        if (removed.get() == null) {
            return;
        }
        ScheduledFuture<?> timer = expiryTimers.remove(sessionId);
        if (timer != null) {
            timer.cancel(false);
        }
        ChatPresence presence = chatPresence.getIfAvailable();
        if (presence != null) {
            try {
                presence.disconnected(user.userId(), sessionId);
            } catch (RuntimeException exception) {
                // 그 연결의 접속 정보는 만료로 사라진다
                log.warn("접속 정보 삭제 실패 userId={}, sessionId={}", user.userId(), sessionId, exception);
            }
        }
    }

    /** {@code expiresAt}에 만료 종료를 예약하고, 재인증 전의 예약이 있으면 취소한다 */
    private void scheduleExpiry(String sessionId, SessionUser user, Instant expiresAt) {
        ScheduledFuture<?> previous;
        try {
            previous = expiryTimers.put(sessionId, expiryScheduler.schedule(() -> expire(sessionId), expiresAt));
        } catch (TaskRejectedException exception) {
            // 앱 종료 중 등. 주기 대조가 닫는다
            log.warn("연결 만료 종료를 예약하지 못함. 주기 대조에 맡긴다 sessionId={}", sessionId, exception);
            previous = null;
        }
        if (previous != null) {
            // 이미 돌기 시작한 옛 예약은 expire가 바뀐 만료를 보고 닫지 않는다
            previous.cancel(false);
        }
        if (user == null || users.get(sessionId) != user) {
            // 예약하는 사이 닫힘 처리가 끝났다
            ScheduledFuture<?> timer = expiryTimers.remove(sessionId);
            if (timer != null) {
                timer.cancel(false);
            }
        }
    }

    /**
     * 만료 예약이 돈다. 재인증으로 만료가 늦춰진 뒤의 옛 예약이면 닫지 않는다(닫기 표시가 잠금 안에서 만료를 다시 본다). 새 예약이 그
     * 만료에 닫는다. 예약 목록의 항목은 새 예약일 수 있어 여기서 지우지 않고, 연결이 닫힐 때 지운다.
     */
    private void expire(String sessionId) {
        closeLater(sessionId, TOKEN_EXPIRED);
    }

    /**
     * 만료·로그아웃 표식에 따른 종료를 닫기 실행기에 맡긴다. 예약·주기 대조·하트비트 스레드는 여기서 돌아오기만 하고 닫기·닫힘 처리를
     * 기다리지 않는다.
     *
     * @return 새로 맡겼으면 true. 이미 닫기를 기다리거나, 대기열이 차서 맡기지 못했으면 false(다음 주기 대조·하트비트가 다시 맡긴다)
     */
    private boolean closeLater(String sessionId, CloseStatus status) {
        SessionUser user = users.get(sessionId);
        if (user == null || markClosing(user, sessionId, status) != ClosingMark.MARKED) {
            return false;
        }
        try {
            expiryCloser.execute(() -> {
                try {
                    if (users.containsKey(sessionId) && closeSession(sessionId, status)) {
                        log.info("채팅 연결을 닫음 sessionId={}, reason={}", sessionId, status.getReason());
                    }
                } finally {
                    expiryClosing.remove(sessionId);
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            expiryClosing.remove(sessionId);
            log.warn("연결 닫기 대기열이 가득 참. 다음 주기 대조가 다시 맡긴다 sessionId={}, reason={}", sessionId,
                    status.getReason());
            return false;
        }
    }

    /**
     * 연결에 닫기를 맡겼다고 표시한다. 재인증과 같은 연결 잠금 안에서 한다. {@link #TOKEN_EXPIRED}면 잠금 안에서 만료를 다시 보고, 재인증으로
     * 늦춰졌으면 표시하지 않는다. 표시가 남아 있는 동안 재인증은 거절된다.
     */
    private ClosingMark markClosing(SessionUser user, String sessionId, CloseStatus status) {
        synchronized (user) {
            if (status == TOKEN_EXPIRED && user.expiresAt().get().isAfter(clock.instant())) {
                return ClosingMark.NOT_EXPIRED;
            }
            return expiryClosing.add(sessionId) ? ClosingMark.MARKED : ClosingMark.ALREADY_CLOSING;
        }
    }

    private enum ClosingMark {
        /** 이번에 닫기를 맡겼다고 표시했다 */
        MARKED,
        /** 이미 닫기를 맡겨 둔 연결이다 */
        ALREADY_CLOSING,
        /** 재인증으로 만료가 늦춰져 닫지 않는다 */
        NOT_EXPIRED
    }

    private void sweepExpired() {
        try {
            int requested = closeExpired();
            if (requested > 0) {
                log.info("만료가 지난 채팅 연결의 닫기를 주기 대조로 맡김 requested={}", requested);
            }
        } catch (RuntimeException exception) {
            log.error("채팅 연결 만료 주기 대조 실패", exception);
        }
    }

    /**
     * 이 연결에 결합한 회원
     *
     * @param authenticatedAt 처음 연결한 연결 티켓의 발급 시각. 로그아웃 표식과 비교한다. 재인증해도 바꾸지 않는다
     * @param expiresAt       연결을 닫을 시각. 재인증이 바꾼다
     * @param stompConnected  CONNECT를 받았는지
     */
    private record SessionUser(Long userId, Instant authenticatedAt, AtomicReference<Instant> expiresAt,
                               AtomicBoolean stompConnected) {
    }

    /** 남아 있는 만료 예약 수(테스트용) */
    int expiryTimerCount() {
        return expiryTimers.size();
    }
}
