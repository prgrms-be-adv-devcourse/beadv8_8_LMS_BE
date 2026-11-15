package com.hapbang.auxiliary.notification.in.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 출연·지갑 앱의 발행 형식 그대로 이벤트를 보내는 시험 도구. value는 shared 레코드의 JSON 바이트, 헤더는 {@code __TypeId__}(레코드
 * 클래스 이름) 하나다. 레코드 timestamp를 정해 보낼 수 있다(사건 시각이 없는 이벤트의 대체 시각, ND-081).
 */
final class AlertEventKafka implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final KafkaProducer<String, byte[]> producer;

    AlertEventKafka(String bootstrapServers) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        this.producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
    }

    void send(String topic, String key, Object event) {
        send(topic, key, event, null);
    }

    /** @param timestamp 레코드 timestamp. null이면 보내는 시각 */
    void send(String topic, String key, Object event, Instant timestamp) {
        send(topic, key, event.getClass().getName(), JSON.writeValueAsString(event), timestamp);
    }

    /** 원본 JSON을 그대로 보낸다(#818 이전 형식처럼 키가 없는 본문, ND-086). typeId가 null이면 타입 헤더를 붙이지 않는다 */
    void sendRaw(String topic, String key, String typeId, String json) {
        send(topic, key, typeId, json, null);
    }

    private void send(String topic, String key, String typeId, String json, Instant timestamp) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, null,
                timestamp == null ? null : timestamp.toEpochMilli(), key, json.getBytes(StandardCharsets.UTF_8));
        if (typeId != null) {
            record.headers().add("__TypeId__", typeId.getBytes(StandardCharsets.UTF_8));
        }
        try {
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("이벤트를 보내지 못했습니다.", e);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
