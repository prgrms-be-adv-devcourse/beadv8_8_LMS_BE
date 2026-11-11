package com.hapbang.auxiliary.notification.in.event;

import static org.awaitility.Awaitility.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.support.KafkaHeaders;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * member-app의 발행 형식 그대로 회원 이벤트를 보내고 DLT를 읽는 시험 도구. key는 회원 ID 문자열, value는 shared 레코드의 JSON 바이트,
 * 헤더는 {@code __TypeId__}(레코드 클래스 이름) 하나다. 수신은 모두 제한 시간 안에서 기다린다.
 */
final class MemberEventKafka implements AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String bootstrapServers;
    private final String topic;
    private final KafkaProducer<String, byte[]> producer;

    MemberEventKafka(String bootstrapServers, String topic) {
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        this.producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
    }

    void send(String typeId, long key, String json) {
        send(typeId, key, json, null);
    }

    /** partition을 정해 보낸다(null이면 key로 고른다) */
    void send(String typeId, long key, String json, Integer partition) {
        send(typeId, key, json, partition, null);
    }

    /** 레코드 timestamp를 정해 보낸다(회원 이벤트에는 사건 시각이 없어 이 값이 알림의 대체 시각이다, ND-081) */
    void sendAt(String typeId, long key, String json, Instant timestamp) {
        send(typeId, key, json, null, timestamp);
    }

    private void send(String typeId, long key, String json, Integer partition, Instant timestamp) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, partition,
                timestamp == null ? null : timestamp.toEpochMilli(), String.valueOf(key),
                json.getBytes(StandardCharsets.UTF_8));
        record.headers().add("__TypeId__", typeId.getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("회원 이벤트를 보내지 못했습니다.", e);
        }
    }

    static String userJson(long id, String nickname, Role role, UserStatus status, Long version) {
        return "{\"user\":{\"id\":" + id + ",\"nickname\":\"" + nickname + "\",\"role\":\"" + role.name()
                + "\",\"status\":\"" + status.name() + "\",\"version\":" + version + "}}";
    }

    /** DLT의 모든 partition에서 key가 같은 레코드를 기다린다 */
    ConsumerRecord<String, byte[]> awaitDeadLetter(String deadLetterTopic, long key) {
        return awaitDeadLetter(deadLetterTopic, String.valueOf(key));
    }

    /** DLT의 모든 partition에서 key(문자열)가 같은 레코드를 기다린다. 출연·지갑 이벤트 key는 {@code <종류>:<ID>}다 */
    ConsumerRecord<String, byte[]> awaitDeadLetter(String deadLetterTopic, String key) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = new ArrayList<>();
            await().atMost(TIMEOUT).until(() -> {
                List<PartitionInfo> infos = consumer.partitionsFor(deadLetterTopic);
                return infos != null && !infos.isEmpty();
            });
            consumer.partitionsFor(deadLetterTopic)
                    .forEach(info -> partitions.add(new TopicPartition(deadLetterTopic, info.partition())));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            await().pollDelay(Duration.ZERO).atMost(TIMEOUT).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
                return !found.isEmpty();
            });
        }
        return found.getFirst();
    }

    /** 지금 DLT에 있는 레코드의 key(처리가 끝난 뒤 부른다). 건너뛴 레코드가 DLT로 가지 않았는지 볼 때 쓴다 */
    List<String> deadLetterKeysNow(String deadLetterTopic) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<String> keys = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = new ArrayList<>();
            consumer.partitionsFor(deadLetterTopic)
                    .forEach(info -> partitions.add(new TopicPartition(deadLetterTopic, info.partition())));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var end = consumer.endOffsets(partitions);
            await().pollDelay(Duration.ZERO).atMost(TIMEOUT).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> keys.add(record.key()));
                return partitions.stream().allMatch(p -> consumer.position(p) >= end.get(p));
            });
        }
        return keys;
    }

    static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** DLT 예외 헤더(예외·원인 클래스 이름). 리스너 예외는 감싸져 오므로 둘을 함께 본다 */
    static String exceptionClasses(ConsumerRecord<?, ?> record) {
        return header(record, KafkaHeaders.DLT_EXCEPTION_FQCN) + " "
                + header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
