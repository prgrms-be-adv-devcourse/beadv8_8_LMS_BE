package com.hapbang.coupon.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.hapbang.coupon.app.port.CouponIssueEventPublisher.PublishResult;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import io.micrometer.observation.ObservationRegistry;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * 발급 요청 이벤트 전송: 실제 Kafka(Testcontainers)로 key·헤더·값과, broker가 멈췄을 때 ACK 대기 안에 시간 초과로 돌아오는지 본다.
 * 공유 broker의 운영 토픽에는 다른 테스트의 발급 소비자가 붙어 있을 수 있어 이 테스트만의 토픽으로 보낸다(운영 토픽 이름은 상수라 따로 보지 않는다).
 */
@SpringJUnitConfig(KafkaCouponIssueEventPublisherTest.Config.class)
class KafkaCouponIssueEventPublisherTest {

    static final String TOPIC = "coupon-publisher-test-" + UUID.randomUUID();
    private static final Instant ISSUED_AT = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private KafkaCouponIssueEventPublisher publisher;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    void ACK를_받으면_쿠폰_ID_key와_eventType과_eventVersion_헤더로_토픽에_싣는다() {
        long couponId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        CouponIssueRequested event = new CouponIssueRequested("req-" + UUID.randomUUID(), couponId, 7, 3, ISSUED_AT,
                ISSUED_AT.plus(Duration.ofDays(30)));

        assertThat(publisher.publish(event, Duration.ofSeconds(10))).isEqualTo(PublishResult.ACKED);

        ConsumerRecord<String, String> record = consume(String.valueOf(couponId));
        assertThat(header(record, "eventType")).isEqualTo("CouponIssueRequested");
        assertThat(header(record, "eventVersion")).isEqualTo("1");
        assertThat(new JsonMapper().readValue(record.value(), CouponIssueRequested.class))
                .isEqualTo(event);
    }

    @Test
    void broker가_멈추면_ACK_대기_안에_시간_초과로_돌아온다() {
        CouponIssueRequested event = new CouponIssueRequested("req-" + UUID.randomUUID(), 1, 7, 1, ISSUED_AT,
                ISSUED_AT.plus(Duration.ofDays(30)));
        AtomicReference<PublishResult> result = new AtomicReference<>();
        long[] elapsedMillis = new long[1];

        KafkaTestcontainersConfig.withBrokerPaused(() -> {
            long start = System.nanoTime();
            result.set(publisher.publish(event, Duration.ofSeconds(1)));
            elapsedMillis[0] = Duration.ofNanos(System.nanoTime() - start).toMillis();
        });

        assertThat(result.get()).isEqualTo(PublishResult.TIMED_OUT);
        assertThat(elapsedMillis[0]).isLessThan(3_000);
    }

    @Test
    void 전송_수명을_고정하고_request_timeout을_전송_수명에서_linger를_뺀_값_이하로_줄인다() {
        Map<String, Object> configs = new HashMap<>(Map.of(ProducerConfig.LINGER_MS_CONFIG, 20));

        KafkaCouponIssueEventPublisher.applyDeliveryTimeout(configs, Duration.ofSeconds(5));

        assertThat(configs).containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4_980);

        // 설정된 request.timeout.ms가 더 작으면 그대로 둔다
        Map<String, Object> shorter = new HashMap<>(Map.of(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000"));
        KafkaCouponIssueEventPublisher.applyDeliveryTimeout(shorter, Duration.ofSeconds(5));
        assertThat(shorter).containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000);

        assertThatThrownBy(() -> KafkaCouponIssueEventPublisher.applyDeliveryTimeout(
                new HashMap<>(Map.of(ProducerConfig.LINGER_MS_CONFIG, 100)), Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ConsumerRecord<String, String> consume(String key) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "coupon-publisher-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (found.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
            }
            assertThat(found).as("key=%s 메시지", key).hasSize(1);
            return found.getFirst();
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(KafkaTestcontainersConfig.class)
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    static class Config {

        @Bean
        NewTopic publisherTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        KafkaCouponIssueEventPublisher publisher(ProducerFactory<?, ?> producerFactory,
                ObjectProvider<DefaultKafkaProducerFactoryCustomizer> customizers,
                ObjectProvider<ObservationRegistry> observationRegistry) {
            return new KafkaCouponIssueEventPublisher(producerFactory, customizers, observationRegistry,
                    CouponIssueDeliveryProperties.defaults(), TOPIC);
        }
    }
}
