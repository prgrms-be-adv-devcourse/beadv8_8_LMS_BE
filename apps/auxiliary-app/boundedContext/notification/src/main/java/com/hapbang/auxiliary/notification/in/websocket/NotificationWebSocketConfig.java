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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
 *     <li>heart-beat: 서버가 보내기·받기 모두 25초(ND-063). 알림이 없어도 프레임이 오가 Gateway stream idle(5분)에 끊기지 않고,
 *     응답 없는 클라이언트는 일찍 닫는다. STOMP 협상이라 클라이언트가 받기를 0으로 두면 서버도 보내지 않는다(FE 계약, ND-068). 설정값은 0 초과 5분 미만이어야
 *     기동한다. 스케줄러는 스레드 1개다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@ConditionalOnNotificationRealtime
public class NotificationWebSocketConfig implements WebSocketMessageBrokerConfigurer, DisposableBean {

    public static final String ENDPOINT = "/api/v1/notifications/ws";
    public static final String QUEUE_PREFIX = "/queue";
    public static final String USER_PREFIX = "/user";
    /**
     * heart-beat 상한(ND-068). Gateway 알림 웹소켓 route의 stream idle(`apps/auxiliary-app/k8s/base/gateway.yaml`, 5분)과
     * 같은 값이다. Gateway 값을 바꾸면 함께 바꾼다
     */
    static final Duration MAX_HEARTBEAT = Duration.ofMinutes(5);

    private final NotificationTicketHandshakeInterceptor ticketInterceptor;
    private final NotificationWebSocketSessions sessions;
    private final NotificationStompInterceptor stompInterceptor;
    private final NotificationOutboundLatencyInterceptor outboundLatencyInterceptor;
    private final List<String> allowedOrigins;
    private final Duration sendTimeLimit;
    private final DataSize sendBufferSizeLimit;
    /** 수신·송신 채널 실행기. 빈이 아니므로 이 설정이 소유하고 닫힐 때 종료한다(chat-app ChatChannelBudget과 같다) */
    private final ThreadPoolTaskExecutor inboundExecutor;
    private final ThreadPoolTaskExecutor outboundExecutor;
    private final Duration heartbeat;
    /** heart-beat 스케줄러. 빈이 아니므로 이 설정이 소유하고 닫힐 때 종료한다 */
    private final ThreadPoolTaskScheduler heartbeatScheduler;

    public NotificationWebSocketConfig(NotificationTicketHandshakeInterceptor ticketInterceptor,
            NotificationWebSocketSessions sessions, NotificationStompInterceptor stompInterceptor,
            NotificationOutboundLatencyInterceptor outboundLatencyInterceptor,
            @Value("${hapbang.notification.websocket.allowed-origins:}") String[] allowedOrigins,
            @Value("${hapbang.notification.realtime.websocket.send-time-limit:10s}") Duration sendTimeLimit,
            @Value("${hapbang.notification.realtime.websocket.send-buffer-size-limit:512KB}") DataSize sendBufferSizeLimit,
            @Value("${hapbang.notification.realtime.websocket.inbound-threads:2}") int inboundThreads,
            @Value("${hapbang.notification.realtime.websocket.outbound-threads:2}") int outboundThreads,
            @Value("${hapbang.notification.realtime.websocket.heartbeat:25s}") Duration heartbeat) {
        // 실행기를 만들기 전에 검사해 실패해도 스레드가 남지 않게 한다
        if (heartbeat.isNegative() || heartbeat.isZero() || heartbeat.compareTo(MAX_HEARTBEAT) >= 0) {
            throw new IllegalArgumentException("hapbang.notification.realtime.websocket.heartbeat는 0보다 크고 "
                    + MAX_HEARTBEAT + "보다 작아야 한다(권장 25s, ND-063·ND-068): " + heartbeat);
        }
        this.ticketInterceptor = ticketInterceptor;
        this.sessions = sessions;
        this.stompInterceptor = stompInterceptor;
        this.outboundLatencyInterceptor = outboundLatencyInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.sendTimeLimit = sendTimeLimit;
        this.sendBufferSizeLimit = sendBufferSizeLimit;
        this.inboundExecutor = channelExecutor("notification-ws-in-", inboundThreads);
        this.outboundExecutor = channelExecutor("notification-ws-out-", outboundThreads);
        this.heartbeat = heartbeat;
        this.heartbeatScheduler = heartbeatScheduler();
    }

    @Override
    public void destroy() {
        inboundExecutor.shutdown();
        outboundExecutor.shutdown();
        heartbeatScheduler.shutdown();
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
        registry.enableSimpleBroker(QUEUE_PREFIX)
                // 보내기·받기 간격(밀리초). 협상은 STOMP 규칙(양쪽 중 큰 값, 한쪽이 0이면 없음)을 따른다
                .setHeartbeatValue(new long[] {heartbeat.toMillis(), heartbeat.toMillis()})
                .setTaskScheduler(heartbeatScheduler);
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
        // 서버 내부 STOMP outbound 처리 지연(ND-162)
        registration.interceptors(outboundLatencyInterceptor);
        registration.executor(outboundExecutor);
    }

    private static ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-ws-heartbeat-");
        scheduler.initialize();
        // 채널 실행기와 같이 기동 때 스레드를 미리 띄워 연결 수와 관계없이 스레드 수를 일정하게 둔다(U13)
        scheduler.getScheduledThreadPoolExecutor().prestartAllCoreThreads();
        return scheduler;
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
