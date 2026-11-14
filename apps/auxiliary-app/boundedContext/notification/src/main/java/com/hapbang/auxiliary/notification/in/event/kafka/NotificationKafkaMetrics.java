package com.hapbang.auxiliary.notification.in.event.kafka;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.NotificationOutcomeRecorder;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * 알림 Kafka 소비 계측(ND-159·ND-161). 태그 {@code topic}은 원본 토픽이다(토픽마다 소비 그룹이 하나). 회원·알림 ID는 태그에 넣지 않는다.
 * <ul>
 *     <li>{@value #CONSUME_FAILURES_METRIC}{topic, exception}: 처리 실패 시도마다 하나. 오류 처리기의 {@link RetryListener}로 세므로
 *     재시도와 역직렬화 실패를 같은 기준으로 센다. {@code exception}은 리스너가 감싼 예외를 벗긴 원인 클래스의 짧은 이름이다.</li>
 *     <li>{@value #DEAD_LETTER_METRIC}{topic, result}: DLT 템플릿의 {@link ProducerListener}로 센다. {@code published}는 producer가
 *     브로커 응답(acks=all의 RecordMetadata)을 받았을 때, {@code failed}는 발행 실패(시간 초과 포함)를 돌려받았을 때다. 복구기의
 *     {@code recovered} 콜백만으로 성공을 정하지 않는다. 복구기가 결과 대기를 포기한 뒤 브로커가 늦게 성공을 돌려주면 한 레코드에
 *     {@code failed}와 {@code published}가 함께 오를 수 있다.</li>
 *     <li>{@value #CONSUME_LATENCY_METRIC}{topic}: 레코드 timestamp부터 성공 처리 완료({@link RecordInterceptor#success})까지. 실패한
 *     시도는 재지 않는다. timestamp가 없으면({@code -1}) 재지 않고, 지금보다 미래면(시계 차이) 0으로 잰다. timestamp 종류
 *     (CreateTime·LogAppendTime)는 토픽 설정을 따른다.</li>
 * </ul>
 * 레코드 처리 앞뒤로 저장 결과 집계의 소비 문맥({@link NotificationOutcomeRecorder})을 열고 닫는다. 계측 실패는 처리에 영향을 주지 않는다.
 */
@Slf4j
@Component
public class NotificationKafkaMetrics {

    public static final String CONSUME_FAILURES_METRIC = "hapbang.notification.consume.failures";
    public static final String DEAD_LETTER_METRIC = "hapbang.notification.dead_letter";
    public static final String CONSUME_LATENCY_METRIC = "hapbang.notification.consume.latency";

    /** 소비 지연 histogram 경계 */
    static final List<Duration> LATENCY_BUCKETS = List.of(Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2_500), Duration.ofSeconds(5),
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(300));

    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final NotificationOutcomeRecorder outcomes;
    private final Clock clock;

    @Autowired
    public NotificationKafkaMetrics(ObjectProvider<MeterRegistry> meterRegistry, NotificationOutcomeRecorder outcomes) {
        this(meterRegistry, outcomes, Clock.systemUTC());
    }

    /** 시계를 직접 넘긴다(테스트) */
    NotificationKafkaMetrics(ObjectProvider<MeterRegistry> meterRegistry, NotificationOutcomeRecorder outcomes,
            Clock clock) {
        this.meterRegistry = meterRegistry;
        this.outcomes = outcomes;
        this.clock = clock;
    }

    /** 오류 처리기에 붙여 처리 실패 시도를 센다 */
    public RetryListener retryListener() {
        return (record, exception, deliveryAttempt) -> safely(() -> {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry != null) {
                registry.counter(CONSUME_FAILURES_METRIC, "topic", record.topic(), "exception",
                        causeOf(exception).getClass().getSimpleName()).increment();
            }
        });
    }

    /** {@code sourceTopic}의 DLT 템플릿에 붙여 DLT 전송 결과를 센다 */
    public <K, V> ProducerListener<K, V> deadLetterListener(String sourceTopic) {
        return new ProducerListener<>() {
            @Override
            public void onSuccess(ProducerRecord<K, V> producerRecord, RecordMetadata recordMetadata) {
                countDeadLetter(sourceTopic, "published");
            }

            @Override
            public void onError(ProducerRecord<K, V> producerRecord, RecordMetadata recordMetadata,
                    Exception exception) {
                countDeadLetter(sourceTopic, "failed");
            }
        };
    }

    /** 컨테이너에 붙여 소비 지연을 재고, 레코드 처리 앞뒤로 저장 결과의 소비 문맥을 연다 */
    public <K, V> RecordInterceptor<K, V> recordInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
                outcomes.enter(record.topic());
                return record;
            }

            @Override
            public void success(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
                safely(() -> recordLatency(record));
            }

            @Override
            public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
                outcomes.exit();
            }
        };
    }

    private void recordLatency(ConsumerRecord<?, ?> record) {
        if (record.timestamp() < 0) {
            return;
        }
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        long millis = Math.max(0, clock.millis() - record.timestamp());
        Timer.builder(CONSUME_LATENCY_METRIC)
                .tag("topic", record.topic())
                .serviceLevelObjectives(LATENCY_BUCKETS.toArray(Duration[]::new))
                .register(registry)
                .record(Duration.ofMillis(millis));
    }

    private void countDeadLetter(String sourceTopic, String result) {
        safely(() -> {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry != null) {
                registry.counter(DEAD_LETTER_METRIC, "topic", sourceTopic, "result", result).increment();
            }
        });
    }

    /** 리스너가 감싼 예외를 벗긴다 */
    private static Throwable causeOf(Exception exception) {
        Throwable cause = exception;
        while (cause instanceof ListenerExecutionFailedException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static void safely(Runnable metric) {
        try {
            metric.run();
        } catch (RuntimeException exception) {
            log.warn("알림 Kafka 소비 지표를 기록하지 못했다(처리에는 영향 없음)", exception);
        }
    }
}
