package com.hapbang.chat.in.event.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * chat-app이 선언하는 Kafka 토픽. 실패 처리를 소유한 소비 앱이라 그룹별 DLT만 선언하고, 원본 토픽은 발행 앱이 선언한다.
 * <p>
 * DLT는 {@code <토픽>.<그룹>.DLT}, partition은 원본과 같은 3개, 보존은 7일이다({@link ChatKafkaConstants}). 이미 있으면 그대로 쓰고
 * (partition을 줄이지 않는다) 공용 KafkaAdmin이 시작할 때 만든다. 만들지 못해도 앱은 뜨고, 회원 이벤트 DLT는
 * {@code ChatMemberEventTopicVerifier}가 로그로 알린다.
 */
@Configuration(proxyBeanMethods = false)
public class ChatKafkaTopicConfig {

    @Bean
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    NewTopic chatMemberEventDeadLetterTopic(
            @Qualifier(ChatKafkaConfig.MEMBER_EVENT_SETTINGS) ChatKafkaConsumerSettings settings) {
        return deadLetterTopic(settings);
    }

    @Bean
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    NewTopic chatAppearanceEventDeadLetterTopic(
            @Qualifier(ChatKafkaConfig.APPEARANCE_EVENT_SETTINGS) ChatKafkaConsumerSettings settings) {
        return deadLetterTopic(settings);
    }

    static NewTopic deadLetterTopic(ChatKafkaConsumerSettings settings) {
        return TopicBuilder.name(settings.deadLetterTopic())
                .partitions(settings.deadLetterPartitions())
                .replicas(settings.deadLetterReplicas())
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(ChatKafkaConstants.DEAD_LETTER_RETENTION.toMillis()))
                .build();
    }
}
