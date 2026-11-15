package com.hapbang.coupon.config;

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import com.hapbang.coupon.app.CouponOrderConsumerIsolationException;
import com.hapbang.coupon.in.event.CouponOrderDeadLetterRecorder;
import com.hapbang.shared.lecture.event.LectureEventTopic;

import io.micrometer.observation.ObservationRegistry;

/**
 * 주문 결제·취소 완료 소비자의 Kafka 설정. 규칙은 docs/architecture/infrastructure/kafka.md의 "소비"·"재시도와 DLT"이고, 모양은 발급 확정
 * 소비자({@link CouponIssueConsumerKafkaConfig})와 같다.
 * <ul>
 *     <li>쿠폰 전용 리스너 컨테이너 팩토리({@value #CONTAINER_FACTORY})를 둔다. 같은 JVM의 강의 리스너가 쓰는 Boot 기본 팩토리·오류 처리기는
 *     건드리지 않는다.</li>
 *     <li>처리에 실패하면 같은 자리에서 제한 재시도({@link CouponOrderConsumerProperties})한 뒤 격리 기록(coupon_dlt_log, 종류 ORDER)을
 *     남기고 그룹별 DLT({@value #DEAD_LETTER_TOPIC})로 보낸다. 기록 → DLT 전송 순서이고, 어느 쪽이든 실패하면 offset을 커밋하지 않고 다시
 *     시도한다. 관리자 재처리는 DLT가 아니라 격리 기록의 원문으로 한다.</li>
 *     <li>역직렬화 실패와 {@link CouponOrderConsumerIsolationException}(없는 사용 시도·회원·주문 불일치·내용 충돌)은 재시도 없이 바로
 *     격리한다.</li>
 *     <li>DLT 토픽은 실패 처리를 소유한 이 소비자가 선언한다(partition 3, 보존 7일). 원본과 같은 partition 번호로 보낸다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CouponOrderConsumerProperties.class)
public class CouponOrderConsumerKafkaConfig {

    /** 소비 그룹. 이름은 {@code <앱>.<목적>}이다 */
    public static final String GROUP = "lecture-app.coupon-order";

    /** 그룹별 DLT: {@code <토픽>.<그룹>.DLT} */
    public static final String DEAD_LETTER_TOPIC = LectureEventTopic.NAME + "." + GROUP + ".DLT";

    public static final String CONTAINER_FACTORY = "couponOrderKafkaListenerContainerFactory";

    /** 리스너 컨테이너 ID. 테스트가 소비를 멈추고 다시 켤 때 찾는다 */
    public static final String LISTENER_ID = "couponOrderConsumer";

    /** DLT로 보내는 producer factory 이름. Kafka producer 지표의 {@code spring_id}로 업무 발행·발급 DLT producer와 구분된다 */
    static final String DEAD_LETTER_PRODUCER_FACTORY = "couponOrderDeadLetterProducerFactory";

    private static final int PARTITIONS = 3;
    private static final Duration DEAD_LETTER_RETENTION = Duration.ofDays(7);
    private static final double RETRY_MULTIPLIER = 2.0;

    @Bean
    NewTopic couponOrderDeadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC)
                .partitions(PARTITIONS)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(DEAD_LETTER_RETENTION.toMillis()))
                .build();
    }

    /**
     * 주문 이벤트 소비 전용 팩토리. Boot 설정(연결·{@code spring.kafka.listener.*}·record interceptor 등)은 Boot configurer로 그대로 넣고
     * 오류 처리기만 쿠폰 것으로 바꾼다. 소비자 factory는 Boot 빈을 써서 Kafka client 지표(소비 lag 등)가 그대로 잡힌다.
     */
    @Bean(CONTAINER_FACTORY)
    @SuppressWarnings("unchecked")
    ConcurrentKafkaListenerContainerFactory<Object, Object> couponOrderKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<?, ?> consumerFactory,
            ProducerFactory<?, ?> producerFactory,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers,
            ObjectProvider<ObservationRegistry> observationRegistry, CouponOrderConsumerProperties properties,
            CouponOrderDeadLetterRecorder deadLetterRecorder) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, (ConsumerFactory<Object, Object>) consumerFactory);
        factory.setCommonErrorHandler(errorHandler(producerFactory, producerFactoryCustomizers, observationRegistry,
                properties, deadLetterRecorder));
        // 직접 만든 팩토리라 받은 메시지의 traceparent를 부모로 consumer span을 만들도록 직접 켠다
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    private static DefaultErrorHandler errorHandler(ProducerFactory<?, ?> producerFactory,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers,
            ObjectProvider<ObservationRegistry> observationRegistry, CouponOrderConsumerProperties properties,
            CouponOrderDeadLetterRecorder deadLetterRecorder) {
        // 역직렬화에 실패한 메시지는 원본 바이트로, 처리에 실패한 메시지는 받은 레코드를 JSON으로 보낸다
        Map<Class<?>, Serializer<?>> valueSerializers = Map.of(
                byte[].class, new ByteArraySerializer(),
                Object.class, new JacksonJsonSerializer<>());
        DefaultKafkaProducerFactory<String, Object> deadLetterProducerFactory = new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(),
                new StringSerializer(),
                new DelegatingByTypeSerializer(valueSerializers, true));
        deadLetterProducerFactory.setBeanName(DEAD_LETTER_PRODUCER_FACTORY);
        // Boot가 자기 ProducerFactory에 적용하는 customizer(지표 listener 등)를 DLT용에도 적용해 DLT 전송이 producer 지표에 잡히게 한다
        producerFactoryCustomizers.orderedStream().forEach(customizer -> customizer.customize(deadLetterProducerFactory));
        KafkaTemplate<String, Object> deadLetterTemplate = new KafkaTemplate<>(deadLetterProducerFactory);
        deadLetterTemplate.setObservationEnabled(true);
        observationRegistry.ifAvailable(deadLetterTemplate::setObservationRegistry);

        DeadLetterPublishingRecoverer deadLetterPublisher = new DeadLetterPublishingRecoverer(deadLetterTemplate,
                (record, exception) -> new TopicPartition(DEAD_LETTER_TOPIC, record.partition()));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(properties.retryAttempts());
        backOff.setInitialInterval(properties.retryInterval().toMillis());
        backOff.setMultiplier(RETRY_MULTIPLIER);
        backOff.setMaxInterval(properties.retryMaxInterval().toMillis());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, exception) -> {
            // 격리 기록을 먼저 남긴다. 기록 뒤 DLT 전송이 실패해 다시 오면 기록은 겹치지 않는다
            deadLetterRecorder.record(record, exception);
            deadLetterPublisher.accept(record, exception);
        }, backOff);
        errorHandler.addNotRetryableExceptions(CouponOrderConsumerIsolationException.class);
        return errorHandler;
    }
}
