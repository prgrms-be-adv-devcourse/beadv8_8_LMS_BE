package com.hapbang.member.global.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
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
import org.springframework.modulith.events.EventExternalizationConfiguration;

import com.hapbang.member.in.RejectedEventException;
import com.hapbang.shared.appearance.event.AppearanceEventTopic;

/**
 * 회원 앱의 Kafka 설정. 규칙은 docs/architecture/infrastructure/kafka.md의 "토픽 규칙"·"발행"·"소비"·"재시도와 DLT"다.
 * <p>
 * 발행 (#416)
 * <ul>
 *     <li>회원 이벤트 토픽은 발행하는 이 앱이 선언한다. broker는 토픽을 자동으로 만들지 않는다.
 *     보존은 broker 기본값(시간 기준)을 따른다.</li>
 *     <li>토픽과 key는 shared 레코드의 {@code @Externalized}가 정한다. 여기서는 Modulith 기본 외부화 설정
 *     ({@code @Externalized} 이벤트를 고른다)에 {@code eventType}·{@code eventVersion} 헤더만 더한다.</li>
 *     <li>헤더 값은 UTF-8 바이트로 넣는다. 문자열로 넣으면 Spring Kafka가 타입 정보 헤더({@code spring_json_header_types})를 더 붙인다.
 *     역직렬화에 쓰는 {@code __TypeId__}는 값 직렬화기({@code member.yml}의 JSON 직렬화기)가 따로 붙인다.</li>
 * </ul>
 * 수신 (#555)
 * <ul>
 *     <li>처리에 실패하면 같은 자리에서 1초부터 최대 10초까지 늘리며 3번 재시도한 뒤 그룹별 DLT로 보낸다.</li>
 *     <li>역직렬화 실패처럼 재시도해도 결과가 같은 예외는 {@link DefaultErrorHandler} 기본 분류대로, 필수 값이 없는 이벤트는
 *     {@link RejectedEventException}으로 바로 DLT로 보낸다.</li>
 *     <li>DLT 토픽은 실패 처리를 소유한 이 앱이 선언한다. 업무 토픽은 발행 앱이 선언한다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class MemberKafkaConfig {

    public static final String MEMBER_EVENTS_TOPIC = "member.events.v1";
    public static final String EVENT_TYPE_HEADER = "eventType";
    public static final String EVENT_VERSION_HEADER = "eventVersion";

    private static final int PARTITIONS = 3;
    /** broker가 하나라 복제본도 하나다. */
    private static final int REPLICAS = 1;
    /** 지금 발행하는 회원 이벤트 레코드의 payload 버전. 깨지는 변경은 새 레코드 타입과 새 버전으로 더한다. */
    private static final String EVENT_VERSION = "1";

    /** 출연 리뷰 공개 이벤트로 회원 출연 평점을 집계하는 소비 그룹 (#555). 이름은 {@code <앱>.<목적>}이다. */
    public static final String APPEARANCE_REVIEW_GROUP = "member-app.appearance-review";
    private static final Duration DEAD_LETTER_RETENTION = Duration.ofDays(7);

    /** 그룹마다 실패를 따로 모아 어느 소비자가 실패했는지 구분한다. Spring Kafka 기본값({@code <토픽>.DLT})은 쓰지 않는다. */
    public static String deadLetterTopic(String topic, String groupId) {
        return topic + "." + groupId + ".DLT";
    }

    @Bean
    NewTopic memberEventsTopic() {
        return TopicBuilder.name(MEMBER_EVENTS_TOPIC)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    /** Modulith 자동 설정과 같은 대상·경로를 쓰고 헤더만 더한다. 이 빈이 있으면 자동 설정의 빈은 만들어지지 않는다. */
    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration(BeanFactory beanFactory) {
        return EventExternalizationConfiguration.defaults(AutoConfigurationPackages.get(beanFactory))
                .headers(event -> Map.of(
                        EVENT_TYPE_HEADER, utf8(event.getClass().getSimpleName()),
                        EVENT_VERSION_HEADER, utf8(EVENT_VERSION)))
                .build();
    }

    @Bean
    NewTopic appearanceReviewDeadLetterTopic() {
        return TopicBuilder.name(deadLetterTopic(AppearanceEventTopic.NAME, APPEARANCE_REVIEW_GROUP))
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
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
        // 원본과 같은 partition 번호로 보낸다. 원본 토픽의 partition이 DLT(3개)보다 많아 번호가 없으면
        // DeadLetterPublishingRecoverer가 전송 전에 확인해 partition을 비우고(-1) key로 고르게 한다(verifyPartition 기본값).
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterTemplate,
                (record, exception) -> new TopicPartition(
                        deadLetterTopic(record.topic(), Objects.requireNonNull(KafkaUtils.getConsumerGroupId(),
                                "소비 스레드 밖에서는 DLT를 정할 수 없다")),
                        record.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(Duration.ofSeconds(1).toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(Duration.ofSeconds(10).toMillis());
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(RejectedEventException.class);
        return errorHandler;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
