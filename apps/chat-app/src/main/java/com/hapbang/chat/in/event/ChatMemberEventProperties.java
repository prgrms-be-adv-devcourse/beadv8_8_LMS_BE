package com.hapbang.chat.in.event;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.hapbang.chat.in.event.kafka.ChatDeadLetters;
import com.hapbang.chat.in.event.kafka.ChatKafkaConsumerSettings;

/**
 * 회원 이벤트 Kafka 소비 설정 ({@code hapbang.chat.member-events.*}, 설계 문서 5.3). chat-app이 소유한다.
 * <p>
 * 기본값은 이 코드에 둔다(application.yaml에 두지 않는다). 값은 Kafka 규칙({@code docs/architecture/infrastructure/kafka.md})을
 * 따른다. 환경마다 다르게 써야 하면 같은 키의 yaml·환경 변수로 덮는다(테스트는 재시도를 줄인다).
 * DLT 이름은 설정이 아니라 규칙({@code <토픽>.<그룹>.DLT})으로 정한다.
 *
 * @param enabled               Kafka 소비를 켤지. 꺼져 있으면 리스너·소비 설정 빈을 만들지 않는다. 기본 true
 *                              (모듈 테스트 설정 {@code hapbang-module-test.yaml}과 로컬 프로필은 끈다)
 * @param topic                 회원 이벤트 토픽. 기본 {@code member.events.v1}
 * @param groupId               소비 그룹. 기본 {@code chat-app.member-replica}
 * @param autoOffsetReset       커밋한 offset이 없을 때 시작 위치. 기본 earliest
 * @param maxRetries            첫 처리 실패 뒤 블로킹 재시도 횟수. 기본 3
 * @param retryInitialInterval  첫 재시도 간격(다음마다 두 배). 기본 1초
 * @param retryMaxInterval      재시도 간격 상한. 기본 10초
 * @param deadLetterSendTimeout DLT 발행 한 번에 쓰는 최대 시간(메타데이터 대기 절반 + 전송 완료 대기 나머지). 넘거나 실패하면 처리
 *                              성공으로 보지 않고 다시 처리한다. 기본 10초, 최소 1초. 미확정 초기값(Q12)
 * @param deadLetterPartitions  chat-app이 만드는 DLT의 파티션 수. 원본과 같은 3이다. 기본 3
 * @param deadLetterReplicas    DLT 복제 수. 브로커 1대라 기본 1
 */
@ConfigurationProperties("hapbang.chat.member-events")
public record ChatMemberEventProperties(
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

    public static final String ENABLED_PROPERTY = "hapbang.chat.member-events.enabled";

    public static final String DEFAULT_TOPIC = "member.events.v1";
    public static final String DEFAULT_GROUP_ID = "chat-app.member-replica";
    public static final String DEFAULT_AUTO_OFFSET_RESET = "earliest";
    public static final long DEFAULT_MAX_RETRIES = 3;
    public static final Duration DEFAULT_RETRY_INITIAL_INTERVAL = Duration.ofSeconds(1);
    public static final Duration DEFAULT_RETRY_MAX_INTERVAL = Duration.ofSeconds(10);
    // 미확정 초기값(Q12)
    public static final Duration DEFAULT_DEAD_LETTER_SEND_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_DEAD_LETTER_PARTITIONS = 3;
    public static final short DEFAULT_DEAD_LETTER_REPLICAS = 1;

    public ChatMemberEventProperties {
        enabled = enabled == null || enabled;
        topic = hasText(topic) ? topic : DEFAULT_TOPIC;
        groupId = hasText(groupId) ? groupId : DEFAULT_GROUP_ID;
        autoOffsetReset = hasText(autoOffsetReset) ? autoOffsetReset : DEFAULT_AUTO_OFFSET_RESET;
        maxRetries = maxRetries == null ? DEFAULT_MAX_RETRIES : maxRetries;
        retryInitialInterval = retryInitialInterval == null ? DEFAULT_RETRY_INITIAL_INTERVAL : retryInitialInterval;
        retryMaxInterval = retryMaxInterval == null ? DEFAULT_RETRY_MAX_INTERVAL : retryMaxInterval;
        deadLetterSendTimeout = deadLetterSendTimeout == null ? DEFAULT_DEAD_LETTER_SEND_TIMEOUT : deadLetterSendTimeout;
        deadLetterPartitions = deadLetterPartitions == null ? DEFAULT_DEAD_LETTER_PARTITIONS : deadLetterPartitions;
        deadLetterReplicas = deadLetterReplicas == null ? DEFAULT_DEAD_LETTER_REPLICAS : deadLetterReplicas;
        validate("hapbang.chat.member-events", maxRetries, deadLetterSendTimeout, deadLetterPartitions,
                deadLetterReplicas);
    }

    static void validate(String prefix, long maxRetries, Duration deadLetterSendTimeout, int deadLetterPartitions,
            short deadLetterReplicas) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException(prefix + ".max-retries는 0 이상이어야 합니다.");
        }
        if (deadLetterSendTimeout.compareTo(ChatDeadLetters.MIN_SEND_TIMEOUT) < 0) {
            throw new IllegalArgumentException(prefix + ".dead-letter-send-timeout은 "
                    + ChatDeadLetters.MIN_SEND_TIMEOUT.toSeconds() + "초 이상이어야 합니다.");
        }
        if (deadLetterPartitions < 1 || deadLetterReplicas < 1) {
            throw new IllegalArgumentException(
                    prefix + ".dead-letter-partitions·dead-letter-replicas는 1 이상이어야 합니다.");
        }
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
