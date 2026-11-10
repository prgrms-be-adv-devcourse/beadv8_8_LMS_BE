package com.hapbang.auxiliary.notification.in.websocket;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.unit.DataSize;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;

import com.hapbang.auxiliary.notification.app.ConditionalOnNotificationRealtime;

/**
 * 알림 모듈 자체 STOMP(ND-027). chat-app 연결을 빌리지 않는다. 연결 주소 {@value #ENDPOINT}, 받는 곳 {@code /user/queue/notifications}.
 * <ul>
 *     <li>핸드셰이크: 허용 출처 검사 → 연결 티켓 사용(ND-028). 출처 검사를 티켓 사용보다 먼저 해 거절된 출처가 티켓을 소모하지 않게 한다.</li>
 *     <li>브로커: 메모리 브로커({@code /queue})와 사용자 목적지({@code /user}). 클라이언트가 보내는 목적지(application prefix)는 없고,
 *     SEND는 {@link NotificationStompInterceptor}가 막는다.</li>
 *     <li>전송: 한 번 보내기 상한 10초, 연결별 보내기 버퍼 512KB(ND-058).</li>
 *     <li>수신·송신 채널 실행기는 스레드 수를 작게 고정한다(기본 각 2, U13 측정 대상). 여러 연결의 프레임을 줄로 처리하므로 trace
 *     decorator를 붙이지 않는다(docs/dev/logging.md).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@ConditionalOnNotificationRealtime
public class NotificationWebSocketConfig implements WebSocketMessageBrokerConfigurer, DisposableBean {

    public static final String ENDPOINT = "/api/v1/notifications/ws";
    public static final String QUEUE_PREFIX = "/queue";
    public static final String USER_PREFIX = "/user";

    private final NotificationTicketHandshakeInterceptor ticketInterceptor;
    private final NotificationWebSocketSessions sessions;
    private final NotificationStompInterceptor stompInterceptor;
    private final List<String> allowedOrigins;
    private final Duration sendTimeLimit;
    private final DataSize sendBufferSizeLimit;
    /** 수신·송신 채널 실행기. 빈이 아니므로 이 설정이 소유하고 닫힐 때 종료한다(chat-app ChatChannelBudget과 같다) */
    private final ThreadPoolTaskExecutor inboundExecutor;
    private final ThreadPoolTaskExecutor outboundExecutor;

    public NotificationWebSocketConfig(NotificationTicketHandshakeInterceptor ticketInterceptor,
            NotificationWebSocketSessions sessions, NotificationStompInterceptor stompInterceptor,
            @Value("${hapbang.notification.websocket.allowed-origins:}") String[] allowedOrigins,
            @Value("${hapbang.notification.realtime.websocket.send-time-limit:10s}") Duration sendTimeLimit,
            @Value("${hapbang.notification.realtime.websocket.send-buffer-size-limit:512KB}") DataSize sendBufferSizeLimit,
            @Value("${hapbang.notification.realtime.websocket.inbound-threads:2}") int inboundThreads,
            @Value("${hapbang.notification.realtime.websocket.outbound-threads:2}") int outboundThreads) {
        this.ticketInterceptor = ticketInterceptor;
        this.sessions = sessions;
        this.stompInterceptor = stompInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.sendTimeLimit = sendTimeLimit;
        this.sendBufferSizeLimit = sendBufferSizeLimit;
        this.inboundExecutor = channelExecutor("notification-ws-in-", inboundThreads);
        this.outboundExecutor = channelExecutor("notification-ws-out-", outboundThreads);
    }

    @Override
    public void destroy() {
        inboundExecutor.shutdown();
        outboundExecutor.shutdown();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = allowedOrigins.toArray(String[]::new);
        registry.addEndpoint(ENDPOINT)
                .setHandshakeHandler(new NotificationHandshakeHandler())
                // Spring이 setAllowedOrigins로 붙이는 출처 검사는 사용자 인터셉터 뒤에 돈다. 티켓을 쓰기 전에 거르려고 앞에 직접 둔다
                .addInterceptors(new OriginHandshakeInterceptor(allowedOrigins), ticketInterceptor)
                .setAllowedOrigins(origins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(QUEUE_PREFIX);
        registry.setUserDestinationPrefix(USER_PREFIX);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessions);
        registration.setSendTimeLimit(Math.toIntExact(sendTimeLimit.toMillis()));
        registration.setSendBufferSizeLimit(Math.toIntExact(sendBufferSizeLimit.toBytes()));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompInterceptor);
        // taskExecutor() 설정은 Boot의 configurer가 넣는 executor(...)에 가려지므로 executor(...)로 직접 넣는다
        registration.executor(inboundExecutor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.executor(outboundExecutor);
    }

    private static ThreadPoolTaskExecutor channelExecutor(String prefix, int threads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setThreadNamePrefix(prefix);
        executor.setAllowCoreThreadTimeOut(false);
        // 고정 크기 풀이라 기동 때 스레드를 미리 띄운다(연결 수와 관계없이 스레드 수가 일정하다, U13 측정)
        executor.setPrestartAllCoreThreads(true);
        executor.initialize();
        return executor;
    }
}
