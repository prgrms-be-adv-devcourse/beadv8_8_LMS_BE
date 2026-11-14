package com.hapbang.auxiliary.notification.in.websocket;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.ConditionalOnNotificationRealtime;
import com.hapbang.auxiliary.notification.app.NotificationRealtimeRelay;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * 서버 내부 STOMP outbound 처리 지연(ND-162): Redis에서 실시간 작업을 받은 시각부터 {@code clientOutboundChannel}이 그 메시지 처리를
 * 마칠 때까지. 지표 {@value #SEND_LATENCY_METRIC}.
 * <ul>
 *     <li>끝은 STOMP 처리기가 프레임을 세션에 넘긴 시점이다. 세션 버퍼 대기, 소켓 쓰기, 클라이언트 수신은 들어 있지 않다(실제 수신까지의
 *     지연은 부하 시험 L3에서 클라이언트로 잰다). STOMP 처리기가 세션 전송 오류를 안에서 처리하면 예외 없이 끝난 것으로 잰다.</li>
 *     <li>처리에 예외가 있었으면 재지 않는다. 수신 시각 헤더가 없는 메시지(CONNECTED·RECEIPT·ERROR 등)도 재지 않는다.</li>
 *     <li>수신 시각은 STOMP native 헤더가 아닌 내부 메시지 헤더({@value #RECEIVED_AT_HEADER})로 넘겨 FE 프레임에 나가지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnNotificationRealtime
public class NotificationOutboundLatencyInterceptor implements ExecutorChannelInterceptor {

    public static final String SEND_LATENCY_METRIC = "hapbang.notification.realtime.send.latency";
    /** Redis 수신 시각({@link System#nanoTime()}). 같은 서버 안에서만 쓴다 */
    public static final String RECEIVED_AT_HEADER = "notificationReceivedAt";

    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final LongSupplier nanoTime;

    @Autowired
    public NotificationOutboundLatencyInterceptor(ObjectProvider<MeterRegistry> meterRegistry) {
        this(meterRegistry, System::nanoTime);
    }

    /** 시계를 직접 넘긴다(테스트) */
    NotificationOutboundLatencyInterceptor(ObjectProvider<MeterRegistry> meterRegistry, LongSupplier nanoTime) {
        this.meterRegistry = meterRegistry;
        this.nanoTime = nanoTime;
    }

    /** Redis에서 받은 시각을 담은 사용자 목적지 전송용 헤더. 보낼 때 그대로 넘긴다 */
    public static SimpMessageHeaderAccessor receivedAt(long nanoTime) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setHeader(RECEIVED_AT_HEADER, nanoTime);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
            Exception exception) {
        if (exception != null || !(message.getHeaders().get(RECEIVED_AT_HEADER) instanceof Long receivedAt)) {
            return;
        }
        try {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry != null) {
                Timer.builder(SEND_LATENCY_METRIC)
                        .serviceLevelObjectives(NotificationRealtimeRelay.LATENCY_BUCKETS.toArray(Duration[]::new))
                        .register(registry)
                        .record(Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - receivedAt)));
            }
        } catch (RuntimeException metricFailure) {
            log.warn("알림 STOMP outbound 처리 지표를 기록하지 못했다(전달에는 영향 없음)", metricFailure);
        }
    }
}
