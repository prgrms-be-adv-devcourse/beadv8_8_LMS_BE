package com.hapbang.chat.in.event.kafka;

import java.time.Duration;

import org.springframework.util.Assert;

/**
 * chat-app Kafka 소비자 하나의 값. 운영 값은 {@link ChatKafkaConfig}가 {@link ChatKafkaConstants}로 만든 빈이고, 세 설정 클래스와
 * 리스너가 이 빈을 이름으로 받는다. 설정 키로 바꾸지 않는다. Kafka 시험은 이 빈만 바꿔(test bean override) 자기 토픽·그룹을 쓰고
 * 재시도를 줄인다.
 *
 * @param topic                 원본 토픽
 * @param groupId               소비 그룹
 * @param deadLetterTopic       그룹별 DLT
 * @param maxRetries            첫 처리 실패 뒤 블로킹 재시도 횟수
 * @param retryInitialInterval  첫 재시도 간격. 다음 재시도마다 두 배로 늘린다
 * @param retryMaxInterval      재시도 간격 상한
 * @param deadLetterSendTimeout DLT 발행 한 번에 쓰는 최대 시간. 최소 1초
 * @param deadLetterPartitions  이 앱이 선언하는 DLT의 partition 수
 * @param deadLetterReplicas    DLT 복제 수
 */
public record ChatKafkaConsumerSettings(
        String topic,
        String groupId,
        String deadLetterTopic,
        int maxRetries,
        Duration retryInitialInterval,
        Duration retryMaxInterval,
        Duration deadLetterSendTimeout,
        int deadLetterPartitions,
        short deadLetterReplicas
) {

    public ChatKafkaConsumerSettings {
        Assert.hasText(topic, "토픽이 필요합니다.");
        Assert.hasText(groupId, "소비 그룹이 필요합니다.");
        Assert.hasText(deadLetterTopic, "DLT가 필요합니다.");
        Assert.isTrue(maxRetries >= 0, "재시도 횟수는 0 이상이어야 합니다.");
        Assert.notNull(retryInitialInterval, "첫 재시도 간격이 필요합니다.");
        Assert.notNull(retryMaxInterval, "재시도 간격 상한이 필요합니다.");
        Assert.notNull(deadLetterSendTimeout, "DLT 발행 대기 시간이 필요합니다.");
        Assert.isTrue(deadLetterSendTimeout.compareTo(ChatKafkaConstants.MIN_DEAD_LETTER_SEND_TIMEOUT) >= 0,
                () -> "DLT 발행 대기 시간은 " + ChatKafkaConstants.MIN_DEAD_LETTER_SEND_TIMEOUT.toSeconds()
                        + "초 이상이어야 합니다: " + deadLetterSendTimeout);
        Assert.isTrue(deadLetterPartitions >= 1 && deadLetterReplicas >= 1, "DLT partition·복제 수는 1 이상이어야 합니다.");
    }

    /** DLT 발행 한 번 중 메타데이터 대기 몫(절반). DLT가 없으면 여기서 막힌다({@code max.block.ms}) */
    public long deadLetterMaxBlockMillis() {
        return deadLetterSendTimeout.toMillis() / 2;
    }

    /** DLT 발행 한 번 중 전송 완료 대기 몫(나머지). {@code delivery.timeout.ms}·{@code request.timeout.ms}·복구기의 결과 대기 */
    public long deadLetterDeliveryMillis() {
        return deadLetterSendTimeout.toMillis() - deadLetterMaxBlockMillis();
    }
}
