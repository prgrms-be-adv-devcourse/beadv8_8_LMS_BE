package com.hapbang.auxiliary.notification.in.event.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import com.hapbang.auxiliary.notification.app.NotificationOutcomeRecorder;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 알림 Kafka 소비의 이름과 DLT 부품(kafka.md "재시도와 DLT", ND-010·ND-047, test-spec NT-KAF-06·NT-ERR-03). 브로커 없이 부품만 본다.
 */
class NotificationKafkaDeadLetterTest {

    @Test
    void 회원_복제본_그룹과_DLT_이름은_앱_목적_규칙을_따른다() {
        assertThat(NotificationKafkaConstants.MEMBER_EVENTS_TOPIC).isEqualTo("member.events.v1");
        assertThat(NotificationKafkaConstants.MEMBER_REPLICA_GROUP)
                .isEqualTo("auxiliary-app.notification-member-replica");
        assertThat(NotificationKafkaConstants.MEMBER_EVENTS_DLT)
                .isEqualTo("member.events.v1.auxiliary-app.notification-member-replica.DLT");
    }

    @Test
    void 운영_소비자_값은_재시도_3회_1초부터_10초_DLT는_원본과_같은_3개_partition이다() {
        NotificationKafkaConsumerSettings settings = new NotificationKafkaConfig("localhost:9092",
                new StaticListableBeanFactory().getBeanProvider(DefaultKafkaConsumerFactoryCustomizer.class),
                new StaticListableBeanFactory().getBeanProvider(DefaultKafkaProducerFactoryCustomizer.class),
                noMetrics())
                .notificationMemberEventSettings();

        assertThat(settings.topic()).isEqualTo(NotificationKafkaConstants.MEMBER_EVENTS_TOPIC);
        assertThat(settings.groupId()).isEqualTo(NotificationKafkaConstants.MEMBER_REPLICA_GROUP);
        assertThat(settings.deadLetterTopic()).isEqualTo(NotificationKafkaConstants.MEMBER_EVENTS_DLT);
        assertThat(settings.maxRetries()).isEqualTo(3);
        assertThat(settings.retryInitialInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(settings.retryMaxInterval()).isEqualTo(Duration.ofSeconds(10));
        // member-app이 member.events.v1을 partition 3개로 선언한다(MemberKafkaConfig). 같은 번호로 보내려면 같은 수여야 한다
        assertThat(settings.deadLetterPartitions()).isEqualTo(3);
    }

    @Test
    void DLT_토픽은_소비자_값의_이름과_partition_수에_7일_보존이다() {
        assertThat(NotificationKafkaTopicConfig.deadLetterTopic(settings(Duration.ofSeconds(10)))).satisfies(topic -> {
            assertThat(topic.name()).isEqualTo("events.v1.app.DLT");
            assertThat(topic.numPartitions()).isEqualTo(3);
            assertThat(topic.replicationFactor()).isEqualTo((short) 1);
            assertThat(topic.configs()).containsEntry("retention.ms", "604800000");
        });
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 복구기는_원본과_같은_partition_번호로_보내고_발행이_실패하면_예외를_던진다() {
        KafkaOperations<Object, Object> template = mock(KafkaOperations.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        DeadLetterPublishingRecoverer recoverer = NotificationKafkaErrorHandlerConfig.deadLetterRecoverer(template,
                settings(Duration.ofSeconds(2)));
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("events.v1", 2, 3L, "7",
                "{}".getBytes(StandardCharsets.UTF_8));

        // DLT 발행 실패는 처리 성공이 아니다(offset을 넘기지 않게 예외)
        assertThatThrownBy(() -> recoverer.accept(record, new IllegalStateException("처리 실패")))
                .isInstanceOf(KafkaException.class);

        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("events.v1.app.DLT");
        assertThat(sent.getValue().partition()).isEqualTo(2);
        assertThat(sent.getValue().key()).isEqualTo("7");
    }

    @Test
    void DLT_발행_대기_시간을_메타데이터_대기와_전송_완료_대기로_나눈다() {
        Map<String, Object> properties = new NotificationKafkaConfig("localhost:9092",
                new StaticListableBeanFactory().getBeanProvider(DefaultKafkaConsumerFactoryCustomizer.class),
                new StaticListableBeanFactory().getBeanProvider(DefaultKafkaProducerFactoryCustomizer.class),
                noMetrics())
                .notificationMemberEventDeadLetterProducerFactory(settings(Duration.ofSeconds(10)))
                .getConfigurationProperties();

        assertThat(properties)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000L)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000)
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> settings(Duration.ofMillis(999)));
    }

    @Test
    void 읽지_못한_원본은_바이트_그대로_DLT에_싣는다() {
        Serializer<Object> serializer = NotificationKafkaConfig.deadLetterValueSerializer();
        byte[] raw = "{not-json".getBytes(StandardCharsets.UTF_8);
        Headers headers = new RecordHeaders();

        assertThat(serializer.serialize("events.v1.app.DLT", headers, raw)).isEqualTo(raw);
    }

    /** 지표 저장소가 없는 계측(설정 값만 보는 시험) */
    private static NotificationKafkaMetrics noMetrics() {
        var none = new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);
        return new NotificationKafkaMetrics(none, new NotificationOutcomeRecorder(none));
    }

    private static NotificationKafkaConsumerSettings settings(Duration sendTimeout) {
        return new NotificationKafkaConsumerSettings("events.v1", "app", "events.v1.app.DLT",
                NotificationKafkaConstants.MAX_RETRIES, NotificationKafkaConstants.RETRY_INITIAL_INTERVAL,
                NotificationKafkaConstants.RETRY_MAX_INTERVAL, sendTimeout,
                NotificationKafkaConstants.DEAD_LETTER_PARTITIONS, NotificationKafkaConstants.DEAD_LETTER_REPLICAS);
    }
}
