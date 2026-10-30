package com.hapbang.chat.in.event.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.Assert;

/**
 * chat-app 소비자의 DLT 부품. 규칙은 {@code docs/architecture/infrastructure/kafka.md}의 "재시도와 DLT"다.
 * <ul>
 *     <li>DLT 이름은 {@code <토픽>.<그룹>.DLT}, 원본과 같은 partition 번호로 보낸다. DLT 토픽은 이 앱이 선언한다(보존 7일).</li>
 *     <li>DLT 발행 한 번이 설정 시간 안에 끝나도록 메타데이터 대기({@code max.block.ms}, DLT가 없으면 여기서 막힌다)에 절반,
 *     전송 완료 대기({@code delivery.timeout.ms}, {@code request.timeout.ms}, 복구기의 결과 대기)에 나머지를 준다.</li>
 *     <li>발행 결과를 기다리고 실패·시간 초과면 예외를 던져 offset을 커밋하지 않는다(DLT 발행 실패는 처리 성공이 아니다).</li>
 *     <li>발행 실패 로그에 key·값을 남기지 않는다. 값에 회원 정보 같은 업무 데이터가 있을 수 있다.</li>
 * </ul>
 */
public final class ChatDeadLetters {

    /** DLT 보존 기간 */
    public static final Duration RETENTION = Duration.ofDays(7);
    /** 이보다 짧으면 나눈 몫이 너무 작아 정상 발행도 실패한다 */
    public static final Duration MIN_SEND_TIMEOUT = Duration.ofSeconds(1);

    private ChatDeadLetters() {
    }

    /** {@code <토픽>.<그룹>.DLT} */
    public static String topicName(String topic, String groupId) {
        return topic + "." + groupId + ".DLT";
    }

    /** 이 앱이 선언하는 DLT 토픽 */
    public static NewTopic newTopic(ChatKafkaConsumerSettings settings) {
        return TopicBuilder.name(settings.deadLetterTopic())
                .partitions(settings.deadLetterPartitions())
                .replicas(settings.deadLetterReplicas())
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(RETENTION.toMillis()))
                .build();
    }

    /** 기본 producer 설정에 DLT 발행 시간 상한을 더한 새 Map. 넘긴 Map은 바꾸지 않는다 */
    public static Map<String, Object> producerProperties(Map<String, Object> base, Duration sendTimeout) {
        Map<String, Object> properties = new HashMap<>(base);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMillis(sendTimeout));
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) deliveryMillis(sendTimeout));
        // delivery.timeout.ms >= linger.ms + request.timeout.ms 이어야 한다. DLT는 드물어 모아 보내지 않는다
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) deliveryMillis(sendTimeout));
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return properties;
    }

    /** 읽지 못한 원본({@code byte[]})은 그대로, 읽은 레코드는 JSON과 {@code __TypeId__} 하나로 보낸다 */
    public static Serializer<Object> valueSerializer() {
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JacksonJsonSerializer<>());
        return new DelegatingByTypeSerializer(delegates, true);
    }

    /** 발행 실패 로그에 key·값을 남기지 않는 DLT 템플릿 */
    public static <K, V> KafkaTemplate<K, V> template(ProducerFactory<K, V> producerFactory) {
        KafkaTemplate<K, V> template = new KafkaTemplate<>(producerFactory);
        LoggingProducerListener<K, V> producerListener = new LoggingProducerListener<>();
        producerListener.setIncludeContents(false);
        template.setProducerListener(producerListener);
        return template;
    }

    /**
     * DLT 복구기. 원본과 같은 partition 번호로 보낸다. DLT의 partition이 그보다 적으면 복구기가 전송 전에 확인해 partition을 비우고
     * (-1) key로 고르게 한다(기본 동작). 결과 대기는 max(delivery.timeout.ms + 여유, 설정값)이라 여유를 0으로 둔다.
     */
    public static DeadLetterPublishingRecoverer recoverer(KafkaOperations<?, ?> template,
            ChatKafkaConsumerSettings settings) {
        String deadLetterTopic = settings.deadLetterTopic();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setTimeoutBuffer(0);
        recoverer.setWaitForSendResultTimeout(Duration.ofMillis(deliveryMillis(settings.deadLetterSendTimeout())));
        return recoverer;
    }

    /** 발행 한 번 중 메타데이터 대기 몫(절반) */
    public static long maxBlockMillis(Duration sendTimeout) {
        Assert.notNull(sendTimeout, "DLT 발행 대기 시간이 필요합니다.");
        Assert.isTrue(sendTimeout.compareTo(MIN_SEND_TIMEOUT) >= 0,
                () -> "DLT 발행 대기 시간은 " + MIN_SEND_TIMEOUT.toSeconds() + "초 이상이어야 합니다: " + sendTimeout);
        return sendTimeout.toMillis() / 2;
    }

    /** 발행 한 번 중 전송 완료 대기 몫(나머지) */
    public static long deliveryMillis(Duration sendTimeout) {
        return sendTimeout.toMillis() - maxBlockMillis(sendTimeout);
    }
}
