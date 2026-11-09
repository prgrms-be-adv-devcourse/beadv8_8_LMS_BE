package com.hapbang.auxiliary.chatbot.in.kafka;

import java.time.Duration;

import com.hapbang.shared.appearance.event.AppearanceEventTopic;

/**
 * 챗봇 Kafka 소비 설정 (#716). 테스트가 토픽·그룹·재시도를 바꿔 끼운다.
 * 이름 규칙은 docs/architecture/infrastructure/kafka.md: 그룹 {@code <앱>.<목적>}, DLT {@code <topic>.<group>.DLT}.
 *
 * @param topic               받는 토픽
 * @param groupId             소비 그룹
 * @param deadLetterTopic     처리하지 못한 메시지를 보내는 토픽
 * @param maxRetries          DLT로 보내기 전 다시 시도하는 횟수
 * @param retryInitialInterval 첫 재시도 간격(두 배씩 늘린다)
 * @param retryMaxInterval     재시도 간격 상한
 * @param deadLetterPartitions DLT 파티션 수
 */
public record ChatbotKafkaSettings(
        String topic,
        String groupId,
        String deadLetterTopic,
        int maxRetries,
        Duration retryInitialInterval,
        Duration retryMaxInterval,
        int deadLetterPartitions
) {

    /** 출연 이벤트를 받아 출연·리뷰 사본에 반영하는 그룹 */
    public static final String APPEARANCE_COPY_GROUP = "auxiliary-app.chatbot-appearance-copy";
    /** 출연 이벤트 신뢰 패키지. 와일드카드 없이 정확히 이 패키지의 타입만 읽는다 */
    public static final String APPEARANCE_TRUSTED_PACKAGE = "com.hapbang.shared.appearance.event";

    public static ChatbotKafkaSettings appearanceDefaults() {
        return of(AppearanceEventTopic.NAME, APPEARANCE_COPY_GROUP, 3, Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    public static ChatbotKafkaSettings of(String topic, String groupId, int maxRetries, Duration retryInitialInterval,
            Duration retryMaxInterval) {
        return new ChatbotKafkaSettings(topic, groupId, topic + "." + groupId + ".DLT", maxRetries, retryInitialInterval,
                retryMaxInterval, 3);
    }
}
