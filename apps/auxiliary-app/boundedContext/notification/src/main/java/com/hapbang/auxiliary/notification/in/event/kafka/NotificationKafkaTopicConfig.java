package com.hapbang.auxiliary.notification.in.event.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 알림 모듈이 선언하는 Kafka 토픽. 실패 처리를 소유한 소비 쪽이라 그룹별 DLT만 선언하고, 원본 토픽은 발행 앱이 선언한다(kafka.md).
 * <p>
 * DLT는 {@code <토픽>.<그룹>.DLT}, partition은 원본과 같은 3개, 보존은 7일이다({@link NotificationKafkaConstants}). 이미 있으면
 * 그대로 쓰고(partition을 줄이지 않는다) 공용 KafkaAdmin이 시작할 때 만든다.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationKafkaTopicConfig {

    @Bean
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    NewTopic notificationMemberEventDeadLetterTopic(
            @Qualifier(NotificationKafkaConfig.MEMBER_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterTopic(settings);
    }

    @Bean
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    NewTopic notificationAppearanceEventDeadLetterTopic(
            @Qualifier(NotificationKafkaConfig.APPEARANCE_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterTopic(settings);
    }

    @Bean
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.BILLING_EVENTS_ENABLED, matchIfMissing = true)
    NewTopic notificationBillingEventDeadLetterTopic(
            @Qualifier(NotificationKafkaConfig.BILLING_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterTopic(settings);
    }

    @Bean
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, matchIfMissing = true)
    NewTopic notificationLectureEventDeadLetterTopic(
            @Qualifier(NotificationKafkaConfig.LECTURE_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterTopic(settings);
    }

    @Bean
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CHAT_EVENTS_ENABLED, matchIfMissing = true)
    NewTopic notificationChatEventDeadLetterTopic(
            @Qualifier(NotificationKafkaConfig.CHAT_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterTopic(settings);
    }

    static NewTopic deadLetterTopic(NotificationKafkaConsumerSettings settings) {
        return TopicBuilder.name(settings.deadLetterTopic())
                .partitions(settings.deadLetterPartitions())
                .replicas(settings.deadLetterReplicas())
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(NotificationKafkaConstants.DEAD_LETTER_RETENTION.toMillis()))
                .build();
    }
}
