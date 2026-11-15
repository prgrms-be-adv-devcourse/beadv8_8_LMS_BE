package com.hapbang.coupon.out.recovery;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.port.CouponIssueBoundaryProbe;
import com.hapbang.coupon.app.port.CouponIssueBoundaryUnavailableException;
import com.hapbang.coupon.config.CouponIssueConsumerKafkaConfig;
import com.hapbang.coupon.config.CouponRecoveryProperties;
import com.hapbang.coupon.domain.RecoveryOffsets;
import com.hapbang.shared.coupon.event.CouponEventTopic;

/**
 * Kafka AdminClient로 복구 경계를 읽는다. 연결 설정은 Boot가 만든 {@link KafkaAdmin}의 설정을 그대로 쓰고, 호출마다 client를 만들고 닫는다
 * (복구 단계에서만 드물게 부르므로 상시 연결을 두지 않는다).
 * <p>
 * 소비 그룹·DLT 이름은 발급 소비자 설정({@link CouponIssueConsumerKafkaConfig})의 값을 그대로 쓴다.
 */
@Component
public class KafkaCouponIssueBoundaryProbe implements CouponIssueBoundaryProbe {

    private static final String CONSUMER_GROUP = CouponIssueConsumerKafkaConfig.GROUP;
    private static final String DLT_TOPIC = CouponIssueConsumerKafkaConfig.DEAD_LETTER_TOPIC;

    private final KafkaAdmin kafkaAdmin;
    private final Duration timeout;

    public KafkaCouponIssueBoundaryProbe(KafkaAdmin kafkaAdmin, CouponRecoveryProperties properties) {
        this.kafkaAdmin = kafkaAdmin;
        this.timeout = properties.kafkaTimeout();
    }

    @Override
    public RecoveryOffsets eventEndOffsets() {
        return withAdmin("발급 요청 토픽 끝 offset", admin -> endOffsets(admin, CouponEventTopic.NAME));
    }

    @Override
    public RecoveryOffsets dltEndOffsets() {
        return withAdmin("DLT 끝 offset", admin -> endOffsets(admin, DLT_TOPIC));
    }

    @Override
    public RecoveryOffsets committedOffsets() {
        return withAdmin("소비 그룹 커밋 offset", admin -> {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(CONSUMER_GROUP)
                    .partitionsToOffsetAndMetadata().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Map<Integer, Long> offsets = new HashMap<>();
            committed.forEach((partition, offset) -> {
                if (CouponEventTopic.NAME.equals(partition.topic()) && offset != null) {
                    offsets.put(partition.partition(), offset.offset());
                }
            });
            return new RecoveryOffsets(offsets);
        });
    }

    private RecoveryOffsets endOffsets(AdminClient admin, String topic) throws Exception {
        TopicDescription description;
        try {
            description = admin.describeTopics(List.of(topic)).allTopicNames()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS).get(topic);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return RecoveryOffsets.empty();
            }
            throw e;
        }
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        description.partitions().forEach(info -> request.put(new TopicPartition(topic, info.partition()), OffsetSpec.latest()));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> result = admin.listOffsets(request).all()
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        Map<Integer, Long> offsets = new HashMap<>();
        result.forEach((partition, info) -> offsets.put(partition.partition(), info.offset()));
        return new RecoveryOffsets(offsets);
    }

    private RecoveryOffsets withAdmin(String action, AdminCall call) {
        Properties config = new Properties();
        config.putAll(kafkaAdmin.getConfigurationProperties());
        int timeoutMs = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs);
        try (AdminClient admin = AdminClient.create(config)) {
            return call.apply(admin);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CouponIssueBoundaryUnavailableException(action + "을 읽는 중 중단됐다", e);
        } catch (Exception e) {
            throw new CouponIssueBoundaryUnavailableException(action + "을 읽지 못했다", e);
        }
    }

    @FunctionalInterface
    private interface AdminCall {
        RecoveryOffsets apply(AdminClient admin) throws Exception;
    }
}
