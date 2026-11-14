package com.hapbang.auxiliary.notification.in.event.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.serializer.DeserializationException;

import com.hapbang.auxiliary.notification.app.NotificationOutcomeRecorder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Kafka 소비 계측(ND-159·ND-161) 단위 시험. test-spec NT-OBS-01(일부)·03(일부)·06·10.
 * <ul>
 *     <li>실패 시도는 감싼 예외를 벗긴 원인 클래스로 센다.</li>
 *     <li>DLT 전송 결과는 producer 응답(성공·실패)으로만 센다.</li>
 *     <li>소비 지연은 성공한 레코드만 timestamp부터 잰다. 미설정 timestamp는 재지 않고 미래 시각은 0이다.</li>
 *     <li>MeterRegistry가 없어도 아무 일도 하지 않는다.</li>
 * </ul>
 */
class NotificationKafkaMetricsTest {

    private static final String TOPIC = "billing.events.v1";
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NotificationKafkaMetrics metrics = new NotificationKafkaMetrics(provider(registry),
            new NotificationOutcomeRecorder(provider(registry)), Clock.fixed(NOW, ZoneOffset.UTC));

    // 실패 시도 (consume.failures)

    @Test
    void 리스너_실패는_감싼_예외를_벗긴_원인_클래스로_시도마다_센다() {
        ConsumerRecord<Object, Object> record = record(NOW.toEpochMilli());
        Exception wrapped = new ListenerExecutionFailedException("listener failed",
                new IllegalStateException("업무 실패"));

        metrics.retryListener().failedDelivery(record, wrapped, 1);
        metrics.retryListener().failedDelivery(record, wrapped, 2);

        assertThat(failures("IllegalStateException")).isEqualTo(2.0);
    }

    @Test
    void 역직렬화_실패는_그_예외_이름으로_센다() {
        metrics.retryListener().failedDelivery(record(NOW.toEpochMilli()),
                new DeserializationException("깨진 본문", new byte[0], false, new RuntimeException()), 1);

        assertThat(failures("DeserializationException")).isEqualTo(1.0);
    }

    // DLT 전송 결과 (dead_letter)

    @Test
    void DLT_전송은_producer가_성공을_돌려주면_published_실패를_돌려주면_failed로_센다() {
        var listener = metrics.<Object, Object>deadLetterListener(TOPIC);
        ProducerRecord<Object, Object> sent = new ProducerRecord<>(TOPIC + ".g.DLT", "k", "v");

        listener.onSuccess(sent, new RecordMetadata(new TopicPartition(TOPIC + ".g.DLT", 0), 0, 0, 0, 1, 1));
        listener.onError(sent, null, new KafkaException("브로커 응답 없음"));
        listener.onError(sent, null, new KafkaException("브로커 응답 없음"));

        assertThat(deadLetter("published")).isEqualTo(1.0);
        assertThat(deadLetter("failed")).isEqualTo(2.0);
    }

    // 소비 지연 (consume.latency)

    @Test
    void 성공한_레코드는_timestamp부터_지금까지를_잰다() {
        RecordInterceptor<Object, Object> interceptor = metrics.recordInterceptor();
        ConsumerRecord<Object, Object> record = record(NOW.minusSeconds(2).toEpochMilli());

        interceptor.success(record, null);

        Timer timer = latency();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2_000.0);
    }

    @Test
    void timestamp가_미설정이면_재지_않는다() {
        metrics.recordInterceptor().success(record(ConsumerRecord.NO_TIMESTAMP), null);

        assertThat(registry.find(NotificationKafkaMetrics.CONSUME_LATENCY_METRIC).timer()).isNull();
    }

    @Test
    void 미래_timestamp는_0으로_잰다() {
        metrics.recordInterceptor().success(record(NOW.plus(Duration.ofSeconds(3)).toEpochMilli()), null);

        assertThat(latency().count()).isEqualTo(1);
        assertThat(latency().totalTime(TimeUnit.MILLISECONDS)).isZero();
    }

    @Test
    void 실패한_레코드는_재지_않는다() {
        metrics.recordInterceptor().failure(record(NOW.toEpochMilli()), new IllegalStateException(), null);

        assertThat(registry.find(NotificationKafkaMetrics.CONSUME_LATENCY_METRIC).timer()).isNull();
    }

    // registry 없음 (NT-OBS-10)

    @Test
    void MeterRegistry가_없으면_아무_일도_하지_않는다() {
        ObjectProvider<MeterRegistry> none = new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);
        NotificationKafkaMetrics empty = new NotificationKafkaMetrics(none, new NotificationOutcomeRecorder(none),
                Clock.fixed(NOW, ZoneOffset.UTC));
        ConsumerRecord<Object, Object> record = record(NOW.toEpochMilli());

        assertThatCode(() -> {
            empty.retryListener().failedDelivery(record, new IllegalStateException(), 1);
            empty.deadLetterListener(TOPIC).onError(new ProducerRecord<>(TOPIC, "v"), null, new KafkaException("x"));
            RecordInterceptor<Object, Object> interceptor = empty.recordInterceptor();
            interceptor.intercept(record, null);
            interceptor.success(record, null);
            interceptor.afterRecord(record, null);
        }).doesNotThrowAnyException();
    }

    private double failures(String exception) {
        Counter counter = registry.find(NotificationKafkaMetrics.CONSUME_FAILURES_METRIC).tag("topic", TOPIC)
                .tag("exception", exception).counter();
        return counter == null ? 0 : counter.count();
    }

    private double deadLetter(String result) {
        Counter counter = registry.find(NotificationKafkaMetrics.DEAD_LETTER_METRIC).tag("topic", TOPIC)
                .tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private Timer latency() {
        Timer timer = registry.find(NotificationKafkaMetrics.CONSUME_LATENCY_METRIC).tag("topic", TOPIC).timer();
        assertThat(timer).isNotNull();
        return timer;
    }

    private static ConsumerRecord<Object, Object> record(long timestamp) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, timestamp, TimestampType.CREATE_TIME, 0, 0, "key", "value",
                new RecordHeaders(), java.util.Optional.empty());
    }

    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        return new StaticListableBeanFactory(Map.of("meterRegistry", registry)).getBeanProvider(MeterRegistry.class);
    }
}
