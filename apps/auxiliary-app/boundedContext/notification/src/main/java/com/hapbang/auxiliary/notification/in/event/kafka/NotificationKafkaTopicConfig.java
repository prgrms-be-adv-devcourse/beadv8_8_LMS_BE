package com.hapbang.auxiliary.notification.in.event.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 알림 모듈이 선언하는 Kafka 토픽. 실패 처리를 소유한 소비 쪽이라 그룹별 DLT만 선언하고, 원본 토픽은 발행 앱이 선언한다(kafka.md).
 * <p>
 * DLT는 {@code <토픽>.<그룹>.DLT}, partition은 원본과 같은 3개, 보존은 7일이다({@link NotificationKafkaConstants}). 이미 있으면
 * 그대로 쓰고(partition을 줄이지 않는다) 공용 KafkaAdmin이 시작할 때 만든다. 소비자마다 DLT 토픽 선언 빈
 * ({@code notification<이름>EventDeadLetterTopic})은 {@link NotificationKafkaConsumerRegistrar}가 {@link #deadLetterTopic}으로
 * 등록한다(ND-180).
 */
@Configuration(proxyBeanMethods = false)
public class NotificationKafkaTopicConfig {

    static NewTopic deadLetterTopic(NotificationKafkaConsumerSettings settings) {
        return TopicBuilder.name(settings.deadLetterTopic())
                .partitions(settings.deadLetterPartitions())
                .replicas(settings.deadLetterReplicas())
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(NotificationKafkaConstants.DEAD_LETTER_RETENTION.toMillis()))
                .build();
    }
}
