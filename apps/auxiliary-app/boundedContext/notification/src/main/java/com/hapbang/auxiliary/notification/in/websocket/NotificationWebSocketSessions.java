package com.hapbang.auxiliary.notification.in.websocket;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import com.hapbang.auxiliary.notification.app.ConditionalOnNotificationRealtime;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicket;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 열린 알림 웹소켓 연결과 연결별 회원·연결 만료(ND-056·ND-057). 같은 서버의 연결만 안다.
 * <ul>
 *     <li>연결이 열리면 핸드셰이크에서 쓴 티켓의 회원·연결 만료를 결합하고, 만료 시각에 {@link #TOKEN_EXPIRED}(4401)로 닫기를 예약한다.
 *     이미 만료가 지났으면 바로 닫는다.</li>
 *     <li>예약이 빠지거나 늦을 때를 위해 {@code expiry-sweep-interval}(기본 30초)마다 만료가 지난 연결을 다시 찾아 닫는다.</li>
 *     <li>서버가 연결을 닫는 경우는 연결 만료뿐이다(ND-057). 재인증·로그아웃 표식·presence는 두지 않는다.</li>
 *     <li>예약·대조 스레드는 1개다. 닫기는 빠른 일이라 따로 실행기를 두지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnNotificationRealtime
public class NotificationWebSocketSessions implements WebSocketHandlerDecoratorFactory, DisposableBean {

    /** 연결 만료로 닫을 때의 종료 코드. FE는 새 티켓으로 다시 연결한다(채팅과 같은 코드) */
    public static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4401, "TOKEN_EXPIRED");

    /** 이 서버에 결합해 열어 둔 연결 수 Gauge */
    public static final String SESSIONS_METRIC = "hapbang.notification.realtime.sessions";

    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private final ThreadPoolTaskScheduler scheduler;
    private final Clock clock;

    public NotificationWebSocketSessions(
            @Value("${hapbang.notification.realtime.websocket.expiry-sweep-interval:30s}") Duration sweepInterval,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = Clock.systemUTC();
        // 이 서버에 결합해 열어 둔 연결 수(ND-162)
        try {
            meterRegistry.ifAvailable(registry -> Gauge.builder(SESSIONS_METRIC, bindings, Map::size).register(registry));
        } catch (RuntimeException exception) {
            log.warn("알림 웹소켓 연결 수 지표를 등록하지 못했다(연결에는 영향 없음)", exception);
        }
        this.scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-ws-expiry-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::sweep, sweepInterval);
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // 결합하지 못했거나 이미 만료라 닫은 연결은 STOMP 처리기에 넘기지 않는다
                if (bind(session)) {
                    super.afterConnectionEstablished(session);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                unbind(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** 연결에 결합한 회원. 결합하지 않은 연결이면 null */
    public Long userIdOf(String sessionId) {
        Binding binding = bindings.get(sessionId);
        return binding == null ? null : binding.userId();
    }

    /** 연결 만료가 지나 닫았으면 true. 결합하지 않은 연결도 닫고 true */
    public boolean closeIfExpired(String sessionId) {
        Binding binding = bindings.get(sessionId);
        if (binding == null) {
            return true;
        }
        if (binding.expiresAt().isAfter(clock.instant())) {
            return false;
        }
        // 닫힘 처리(afterConnectionClosed)가 결합을 지운다
        close(binding.session(), TOKEN_EXPIRED);
        return true;
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }

    /** @return 결합해 열어 둔 연결이면 true. 티켓이 없거나 이미 만료라 닫았으면 false */
    private boolean bind(WebSocketSession session) {
        if (!(session.getAttributes().get(NotificationTicketHandshakeInterceptor.CONNECTION_ATTRIBUTE)
                instanceof ConnectionTicket ticket)) {
            // 핸드셰이크가 티켓 없이 열린 연결을 막으므로 방어 검사다
            close(session, CloseStatus.POLICY_VIOLATION);
            return false;
        }
        if (!ticket.connectionExpiresAt().isAfter(clock.instant())) {
            close(session, TOKEN_EXPIRED);
            return false;
        }
        // 먼저 결합하고 예약한다. 예약이 곧바로 돌아도 결합을 찾아 닫는다
        bindings.put(session.getId(), new Binding(session, ticket.userId(), ticket.connectionExpiresAt(), null));
        ScheduledFuture<?> expiry = scheduler.schedule(() -> closeIfExpired(session.getId()),
                ticket.connectionExpiresAt());
        if (bindings.computeIfPresent(session.getId(),
                (id, current) -> new Binding(current.session(), current.userId(), current.expiresAt(), expiry)) == null) {
            // 그 사이 닫혔으면 예약을 남기지 않는다
            expiry.cancel(false);
        }
        return true;
    }

    private void unbind(String sessionId) {
        Binding removed = bindings.remove(sessionId);
        if (removed != null && removed.expiry() != null) {
            removed.expiry().cancel(false);
        }
    }

    private void sweep() {
        Instant now = clock.instant();
        bindings.values().stream()
                .filter(binding -> !binding.expiresAt().isAfter(now))
                .forEach(binding -> close(binding.session(), TOKEN_EXPIRED));
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException | RuntimeException exception) {
            log.debug("알림 웹소켓 연결을 닫지 못함 sessionId={}", session.getId(), exception);
        }
    }

    private record Binding(WebSocketSession session, Long userId, Instant expiresAt, ScheduledFuture<?> expiry) {
    }
}
