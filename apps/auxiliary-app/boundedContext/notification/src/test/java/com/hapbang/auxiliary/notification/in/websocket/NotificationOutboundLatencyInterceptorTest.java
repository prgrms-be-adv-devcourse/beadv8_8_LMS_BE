package com.hapbang.auxiliary.notification.in.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 서버 내부 STOMP outbound 처리 지연(ND-162) 단위 시험. test-spec NT-OBS-07(일부).
 * Redis 수신 시각 헤더가 있는 메시지를 outbound 채널이 예외 없이 처리했을 때만 잰다.
 */
class NotificationOutboundLatencyInterceptorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    private final NotificationOutboundLatencyInterceptor interceptor = new NotificationOutboundLatencyInterceptor(
            provider(registry), nanos::get);

    @Test
    void 수신_시각이_있는_메시지를_예외_없이_처리하면_수신부터_처리_완료까지를_잰다() {
        Message<byte[]> message = messageReceivedAt(nanos.get());
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(30));

        interceptor.afterMessageHandled(message, null, null, null);

        Timer timer = registry.find(NotificationOutboundLatencyInterceptor.SEND_LATENCY_METRIC).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(30.0);
    }

    @Test
    void 처리에_실패한_메시지는_재지_않는다() {
        Message<byte[]> message = messageReceivedAt(nanos.get());

        interceptor.afterMessageHandled(message, null, null, new MessageDeliveryException("세션 전송 실패(시험)"));

        assertThat(registry.find(NotificationOutboundLatencyInterceptor.SEND_LATENCY_METRIC).timer()).isNull();
    }

    @Test
    void 수신_시각이_없는_메시지는_재지_않는다() {
        Message<byte[]> connected = MessageBuilder.createMessage(new byte[0],
                SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK).getMessageHeaders());

        interceptor.afterMessageHandled(connected, null, null, null);

        assertThat(registry.find(NotificationOutboundLatencyInterceptor.SEND_LATENCY_METRIC).timer()).isNull();
    }

    @Test
    void 수신_시각_헤더는_STOMP_native_헤더가_아니다() {
        SimpMessageHeaderAccessor accessor = NotificationOutboundLatencyInterceptor.receivedAt(nanos.get());

        assertThat(accessor.toNativeHeaderMap()).isEmpty();
        assertThat(accessor.getHeader(NotificationOutboundLatencyInterceptor.RECEIVED_AT_HEADER)).isEqualTo(nanos.get());
    }

    private static Message<byte[]> messageReceivedAt(long nanoTime) {
        SimpMessageHeaderAccessor accessor = NotificationOutboundLatencyInterceptor.receivedAt(nanoTime);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        return new StaticListableBeanFactory(Map.of("meterRegistry", registry)).getBeanProvider(MeterRegistry.class);
    }
}
