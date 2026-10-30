package com.hapbang.chat.in.event.kafka;

import java.time.Duration;

/**
 * chat-app Kafka 소비자 하나의 설정. 규칙은 {@code docs/architecture/infrastructure/kafka.md}의 "소비"·"재시도와 DLT"다.
 * 소비자마다 자기 {@code @ConfigurationProperties} 레코드가 이 계약을 구현하고, 기본값은 그 레코드의 코드에 둔다.
 */
public interface ChatKafkaConsumerSettings {

    String topic();

    /** 소비 그룹. 이름은 {@code <앱>.<목적>}이다 */
    String groupId();

    String autoOffsetReset();

    /** 첫 처리 실패 뒤 블로킹 재시도 횟수 */
    Long maxRetries();

    /** 첫 재시도 간격. 다음 재시도마다 두 배로 늘린다 */
    Duration retryInitialInterval();

    /** 재시도 간격 상한 */
    Duration retryMaxInterval();

    /** DLT 발행 한 번에 쓰는 최대 시간 */
    Duration deadLetterSendTimeout();

    Integer deadLetterPartitions();

    Short deadLetterReplicas();

    /** 그룹별 DLT 이름 {@code <토픽>.<그룹>.DLT}. Spring Kafka 기본값({@code <토픽>.DLT})은 쓰지 않는다 */
    default String deadLetterTopic() {
        return ChatDeadLetters.topicName(topic(), groupId());
    }
}
