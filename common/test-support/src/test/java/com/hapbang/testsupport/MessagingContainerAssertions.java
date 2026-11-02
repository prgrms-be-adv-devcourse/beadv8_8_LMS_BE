package com.hapbang.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

final class MessagingContainerAssertions {

    private MessagingContainerAssertions() {
    }

    static void assertKafkaRoundTrip(KafkaTemplate<Object, Object> template,
            ConsumerFactory<Object, Object> consumerFactory, KafkaAdmin kafkaAdmin) throws Exception {
        String topic = "test-" + UUID.randomUUID();
        String group = "test-" + UUID.randomUUID();

        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            try (Consumer<Object, Object> consumer = consumerFactory.createConsumer(group, "test")) {
                consumer.subscribe(List.of(topic));
                template.send(topic, "key", "message").get(10, TimeUnit.SECONDS);

                await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                        assertThat(consumer.poll(Duration.ofMillis(250)).records(topic))
                                .singleElement().satisfies(record -> {
                                    assertThat(record.key()).isEqualTo("key");
                                    assertThat(record.value()).isEqualTo("message");
                                }));
            } finally {
                admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
                admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    static void assertRedisRoundTrip(StringRedisTemplate redis) {
        String key = "test:value:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "message");
            assertThat(redis.opsForValue().get(key)).isEqualTo("message");
        } finally {
            redis.delete(key);
        }
    }

    // Spring Data의 read 메서드는 StreamOffset<K>... 형태의 제네릭 가변 인자를 받는다.
    @SuppressWarnings("unchecked")
    static void assertRedisStreamAcknowledgement(StringRedisTemplate redis) {
        String stream = "test:stream:" + UUID.randomUUID();
        String group = "test-" + UUID.randomUUID();
        StreamOperations<String, String, String> streams = redis.opsForStream();
        try {
            var id = streams.add(stream, Map.of("message", "hello"));
            streams.createGroup(stream, ReadOffset.from("0-0"), group);
            var records = streams.read(
                    org.springframework.data.redis.connection.stream.Consumer.from(group, "consumer"),
                    StreamReadOptions.empty().count(1), StreamOffset.create(stream, ReadOffset.lastConsumed()));

            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.getId()).isEqualTo(id);
                assertThat(record.getValue()).containsEntry("message", "hello");
            });
            assertThat(streams.pending(stream, group).getTotalPendingMessages()).isEqualTo(1);
            assertThat(streams.acknowledge(stream, group, id)).isEqualTo(1);
            assertThat(streams.pending(stream, group).getTotalPendingMessages()).isZero();
        } finally {
            redis.delete(stream);
        }
    }
}
