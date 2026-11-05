package com.hapbang.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class EventLogContextRecordInterceptorTest {

    private final EventLogContextRecordInterceptor<String, Object> interceptor = new EventLogContextRecordInterceptor<>();
    @SuppressWarnings("unchecked")
    private final Consumer<String, Object> consumer = mock(Consumer.class);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 헤더의_이벤트_종류와_레코드_key를_MDC에_넣고_레코드_처리가_끝나면_지운다() {
        ConsumerRecord<String, Object> record = record("exchange:45", "ExchangeCompletedEvent");

        interceptor.intercept(record, consumer);

        assertThat(MDC.get(EventLogContextRecordInterceptor.EVENT_TYPE)).isEqualTo("ExchangeCompletedEvent");
        assertThat(MDC.get(EventLogContextRecordInterceptor.EVENT_KEY)).isEqualTo("exchange:45");

        interceptor.afterRecord(record, consumer);

        assertThat(MDC.get(EventLogContextRecordInterceptor.EVENT_TYPE)).isNull();
        assertThat(MDC.get(EventLogContextRecordInterceptor.EVENT_KEY)).isNull();
    }

    @Test
    void 헤더와_key가_없는_레코드는_넣지_않고_앞_레코드의_값도_남기지_않는다() {
        interceptor.intercept(record("exchange:45", "ExchangeCompletedEvent"), consumer);

        interceptor.intercept(record(null, null), consumer);

        assertThat(MDC.get(EventLogContextRecordInterceptor.EVENT_TYPE)).isNull();
        assertThat(MDC.get(EventLogContextRecordInterceptor.EVENT_KEY)).isNull();
    }

    @Test
    void 처리_중_예외로_afterRecord가_불리지_않아도_poll_반복이_끝나면_지운다() {
        interceptor.intercept(record("order-item:123", "LectureObjectionResolvedEvent"), consumer);

        interceptor.clearThreadState(consumer);

        assertThat(MDC.get(EventLogContextRecordInterceptor.EVENT_TYPE)).isNull();
        assertThat(MDC.get(EventLogContextRecordInterceptor.EVENT_KEY)).isNull();
    }

    @Test
    void 다른_MDC_값은_건드리지_않는다() {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");

        interceptor.intercept(record("exchange:45", "ExchangeCompletedEvent"), consumer);
        interceptor.afterRecord(record("exchange:45", "ExchangeCompletedEvent"), consumer);

        assertThat(MDC.get("traceId")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    private static ConsumerRecord<String, Object> record(String key, String eventType) {
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("billing.events.v1", 0, 0L, key, "{}");
        if (eventType != null) {
            record.headers().add(EventLogContextRecordInterceptor.EVENT_TYPE_HEADER,
                    eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
