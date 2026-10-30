package com.hapbang.billing.global.config;

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
 * 지갑 앱의 Kafka 소비 설정. 규칙은 docs/architecture/infrastructure/kafka.md의 "소비"·"재시도와 DLT"다 (#339, #430).
 * <ul>
 *     <li>회원 이벤트({@code member.events.v1})를 목적별 소비 그룹 둘로 받는다(BD-123). 같은 그룹에 리스너 둘을 두면 partition을
 *     나눠 가져 각자 메시지 일부만 받기 때문이다. 한 그룹의 실패는 다른 그룹 처리를 막지 않는다.</li>
 *     <li>처리에 실패하면 같은 자리에서 1초부터 최대 10초까지 늘리며 3번 재시도한 뒤 그룹별 DLT로 보낸다.
 *     재시도하는 동안 그 partition의 뒤 메시지는 기다리므로 같은 회원의 순서가 지켜진다.</li>
 *     <li>역직렬화 실패처럼 재시도해도 결과가 같은 예외는 {@link DefaultErrorHandler} 기본 분류대로 바로 DLT로 보낸다.</li>
 *     <li>DLT 토픽은 실패 처리를 소유한 이 앱이 선언한다. 업무 토픽은 발행 앱(member-app)이 선언한다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class BillingKafkaConfig {

    public static final String MEMBER_EVENTS_TOPIC = "member.events.v1";

    /** 회원 가입·변경 → Billing 회원 복제본({@code billing_user}) 갱신. 이름은 {@code <앱>.<목적>}이다. */
    public static final String MEMBER_REPLICA_GROUP = "billing-app.member-replica";

    /** 회원 가입 → 지갑 생성 */
    public static final String WALLET_CREATION_GROUP = "billing-app.wallet-creation";

    private static final int PARTITIONS = 3;
    private static final Duration DEAD_LETTER_RETENTION = Duration.ofDays(7);

    /** 그룹마다 실패를 따로 모아 어느 소비자가 실패했는지 구분한다. Spring Kafka 기본값({@code <토픽>.DLT})은 쓰지 않는다. */
    public static String deadLetterTopic(String topic, String groupId) {
        return topic + "." + groupId + ".DLT";
    }

    @Bean
    NewTopic memberReplicaDeadLetterTopic() {
        return deadLetterTopic(MEMBER_REPLICA_GROUP);
    }

    @Bean
    NewTopic walletCreationDeadLetterTopic() {
        return deadLetterTopic(WALLET_CREATION_GROUP);
    }

    private static NewTopic deadLetterTopic(String groupId) {
        return TopicBuilder.name(deadLetterTopic(MEMBER_EVENTS_TOPIC, groupId))
                .partitions(PARTITIONS)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(DEAD_LETTER_RETENTION.toMillis()))
                .build();
    }

    /**
     * Boot가 리스너 컨테이너 팩토리에 넣는 오류 처리기. 두 그룹이 함께 쓰고, DLT는 처리 중인 소비 스레드의 그룹으로 고른다.
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
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
