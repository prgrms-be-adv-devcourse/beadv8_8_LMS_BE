package com.hapbang.chat.in.event;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.hapbang.chat.in.event.kafka.ChatKafkaConsumerSettings;

/**
 * 출연 이벤트 Kafka 소비 설정 ({@code hapbang.chat.appearance-events.*}, 설계 문서 5.5). chat-app이 소유한다.
 * <p>
 * 기본값은 이 코드에 둔다(application.yaml에 두지 않는다). 값은 Kafka 규칙({@code docs/architecture/infrastructure/kafka.md})을
 * 따르고 회원 이벤트 소비({@link ChatMemberEventProperties})와 같은 기본값을 쓴다. DLT 이름은 규칙({@code <토픽>.<그룹>.DLT})으로 정한다.
 *
 * @param enabled               Kafka 소비를 켤지. 기본 true(모듈 테스트 설정과 로컬 프로필은 끈다)
 * @param topic                 출연 이벤트 토픽. 기본 {@code appearance.events.v1}
 * @param groupId               소비 그룹. 기본 {@code chat-app.appearance-notification}
 * @param autoOffsetReset       기본 earliest
 * @param maxRetries            기본 3
 * @param retryInitialInterval  기본 1초
 * @param retryMaxInterval      기본 10초
 * @param deadLetterSendTimeout 기본 10초, 최소 1초. 미확정 초기값(Q12)
 * @param deadLetterPartitions  기본 3
 * @param deadLetterReplicas    기본 1
 */
@ConfigurationProperties("hapbang.chat.appearance-events")
public record ChatAppearanceEventProperties(
        Boolean enabled,
        String topic,
        String groupId,
        String autoOffsetReset,
        Long maxRetries,
        Duration retryInitialInterval,
        Duration retryMaxInterval,
        Duration deadLetterSendTimeout,
        Integer deadLetterPartitions,
        Short deadLetterReplicas
) implements ChatKafkaConsumerSettings {

    public static final String ENABLED_PROPERTY = "hapbang.chat.appearance-events.enabled";

    public static final String DEFAULT_TOPIC = "appearance.events.v1";
    public static final String DEFAULT_GROUP_ID = "chat-app.appearance-notification";

    public ChatAppearanceEventProperties {
        enabled = enabled == null || enabled;
        topic = ChatMemberEventProperties.hasText(topic) ? topic : DEFAULT_TOPIC;
        groupId = ChatMemberEventProperties.hasText(groupId) ? groupId : DEFAULT_GROUP_ID;
        autoOffsetReset = ChatMemberEventProperties.hasText(autoOffsetReset) ? autoOffsetReset
                : ChatMemberEventProperties.DEFAULT_AUTO_OFFSET_RESET;
        maxRetries = maxRetries == null ? ChatMemberEventProperties.DEFAULT_MAX_RETRIES : maxRetries;
        retryInitialInterval = retryInitialInterval == null ? ChatMemberEventProperties.DEFAULT_RETRY_INITIAL_INTERVAL
                : retryInitialInterval;
        retryMaxInterval = retryMaxInterval == null ? ChatMemberEventProperties.DEFAULT_RETRY_MAX_INTERVAL
                : retryMaxInterval;
        deadLetterSendTimeout = deadLetterSendTimeout == null
                ? ChatMemberEventProperties.DEFAULT_DEAD_LETTER_SEND_TIMEOUT : deadLetterSendTimeout;
        deadLetterPartitions = deadLetterPartitions == null ? ChatMemberEventProperties.DEFAULT_DEAD_LETTER_PARTITIONS
                : deadLetterPartitions;
        deadLetterReplicas = deadLetterReplicas == null ? ChatMemberEventProperties.DEFAULT_DEAD_LETTER_REPLICAS
                : deadLetterReplicas;
        ChatMemberEventProperties.validate("hapbang.chat.appearance-events", maxRetries, deadLetterSendTimeout,
                deadLetterPartitions, deadLetterReplicas);
    }
}
