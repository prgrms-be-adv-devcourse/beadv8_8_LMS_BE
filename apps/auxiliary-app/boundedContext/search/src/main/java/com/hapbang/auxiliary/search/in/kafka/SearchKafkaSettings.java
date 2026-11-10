package com.hapbang.auxiliary.search.in.kafka;

import java.time.Duration;

/**
 * 검색 Kafka 소비 설정(#771). 테스트가 토픽·그룹·재시도를 바꿔 끼운다. 챗봇 {@code ChatbotKafkaSettings}를 복사했다.
 * 이름 규칙은 {@code docs/architecture/infrastructure/kafka.md}: 그룹 {@code <앱>.<목적>}, DLT {@code <topic>.<group>.DLT}.
 *
 * @param topic                받는 토픽
 * @param groupId              소비 그룹
 * @param deadLetterTopic      처리하지 못한 메시지를 보내는 토픽
 * @param maxRetries           DLT로 보내기 전 다시 시도하는 횟수
 * @param retryInitialInterval 첫 재시도 간격(두 배씩 늘린다)
 * @param retryMaxInterval     재시도 간격 상한
 * @param deadLetterPartitions DLT 파티션 수(원본과 같은 파티션 번호로 보낸다)
 */
public record SearchKafkaSettings(
        String topic,
        String groupId,
        String deadLetterTopic,
        int maxRetries,
        Duration retryInitialInterval,
        Duration retryMaxInterval,
        int deadLetterPartitions
) {

    /** 회원 이벤트 토픽(member-app이 partition 3개로 선언) */
    public static final String MEMBER_EVENTS_TOPIC = "member.events.v1";
    /** 회원 이벤트로 인플루언서 검색 문서를 만드는 그룹 */
    public static final String MEMBER_GROUP = "auxiliary-app.search-member";
    /** 회원 이벤트 신뢰 패키지. 와일드카드 없이 정확히 이 패키지의 타입만 읽는다 */
    public static final String MEMBER_TRUSTED_PACKAGE = "com.hapbang.shared.member.event";

    public static SearchKafkaSettings memberDefaults() {
        return of(MEMBER_EVENTS_TOPIC, MEMBER_GROUP, 3, Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    public static SearchKafkaSettings of(String topic, String groupId, int maxRetries, Duration retryInitialInterval,
            Duration retryMaxInterval) {
        return new SearchKafkaSettings(topic, groupId, topic + "." + groupId + ".DLT", maxRetries, retryInitialInterval,
                retryMaxInterval, 3);
    }
}
