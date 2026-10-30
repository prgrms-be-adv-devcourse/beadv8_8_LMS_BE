package com.hapbang.lecture.config;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * 강의 앱의 Kafka 소비 설정. 규칙은 docs/architecture/infrastructure/kafka.md의 "소비"·"재시도와 DLT"다 (#339, #429).
 * <ul>
 *     <li>처리에 실패하면 같은 자리에서 1초부터 최대 10초까지 늘리며 3번 재시도한 뒤 그룹별 DLT로 보낸다.
 *     재시도하는 동안 그 partition의 뒤 메시지는 기다리므로 같은 회원의 순서가 지켜진다.</li>
 *     <li>역직렬화 실패처럼 재시도해도 결과가 같은 예외는 {@link DefaultErrorHandler} 기본 분류대로 바로 DLT로 보낸다.</li>
 *     <li>DLT 토픽은 실패 처리를 소유한 이 앱이 선언한다. 업무 토픽은 발행 앱이 선언한다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class LectureKafkaConfig {

    public static final String MEMBER_EVENTS_TOPIC = "member.events.v1";

    /** 강의 회원 복제본을 갱신하는 소비 그룹. 이름은 {@code <앱>.<목적>}이다. */
    public static final String MEMBER_REPLICA_GROUP = "lecture-app.member-replica";

    private static final int PARTITIONS = 3;
    private static final Duration DEAD_LETTER_RETENTION = Duration.ofDays(7);

    /** 그룹마다 실패를 따로 모아 어느 소비자가 실패했는지 구분한다. Spring Kafka 기본값({@code <토픽>.DLT})은 쓰지 않는다. */
    public static String deadLetterTopic(String topic, String groupId) {
        return topic + "." + groupId + ".DLT";
    }

    @Bean
    NewTopic memberReplicaDeadLetterTopic() {
        return TopicBuilder.name(deadLetterTopic(MEMBER_EVENTS_TOPIC, MEMBER_REPLICA_GROUP))
                .partitions(PARTITIONS)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(DEAD_LETTER_RETENTION.toMillis()))
                .build();
    }

    /**
     * Boot가 리스너 컨테이너 팩토리에 넣는 오류 처리기.
     * <p>
     * DLT로 보내는 template은 빈으로 두지 않는다. 빈으로 두면 Boot의 기본 {@code KafkaTemplate}·{@code ProducerFactory}를 대신하게 된다.
     * 역직렬화에 실패한 메시지는 원본 바이트로, 처리에 실패한 메시지는 받은 레코드를 JSON으로 보낸다.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(ProducerFactory<?, ?> producerFactory) {
        Map<Class<?>, Serializer<?>> valueSerializers = Map.of(
                byte[].class, new ByteArraySerializer(),
                Object.class, new JacksonJsonSerializer<>());
        KafkaTemplate<String, Object> deadLetterTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(),
                new StringSerializer(),
                new DelegatingByTypeSerializer(valueSerializers, true)));

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterTemplate,
                (record, exception) -> new TopicPartition(
                        deadLetterTopic(record.topic(), Objects.requireNonNull(KafkaUtils.getConsumerGroupId(),
                                "소비 스레드 밖에서는 DLT를 정할 수 없다")),
                        record.partition()));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(Duration.ofSeconds(1).toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(Duration.ofSeconds(10).toMillis());
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
