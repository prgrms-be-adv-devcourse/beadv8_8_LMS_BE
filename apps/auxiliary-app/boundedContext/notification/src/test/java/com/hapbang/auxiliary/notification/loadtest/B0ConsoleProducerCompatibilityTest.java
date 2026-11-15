package com.hapbang.auxiliary.notification.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;

/**
 * B0 투입 도구 호환성(N13, test-spec NT-B0-08, G8). 운영 broker와 같은 {@code apache/kafka:4.4.0} 이미지 안의
 * {@code kafka-console-producer.sh}(kafka-clients 4.4.0)로 B0 줄을 넣고, 실제로 기록된 레코드가 생성기의 기대(발행 앱과 같은
 * kafka-clients 4.2.1 내장 파티셔너)와 같은지 본다.
 * <ul>
 *     <li>producer 설정 파일(b0.sh와 같은 파일)의 값이 실제 producer 프로세스에 적용됐다(ProducerConfig 로그).</li>
 *     <li>레코드마다 토픽·파티션이 기대와 같고, 파티션 안 offset 순서가 파일 순서와 같다.</li>
 *     <li>key 바이트, {@code __TypeId__} 헤더, 본문 바이트가 생성한 값과 같다.</li>
 *     <li>어긋나면 기대값을 고치지 않고 원인(버전·설정·직렬화)을 확인한다.</li>
 * </ul>
 */
class B0ConsoleProducerCompatibilityTest {

    private static final String IMAGE = "apache/kafka:4.4.0";
    private static final int PER_TOPIC = 200;
    private static final Path LOADTEST = Path.of("../../../../loadtest/notification").toAbsolutePath().normalize();

    private static KafkaContainer kafka;

    @BeforeAll
    static void start() throws Exception {
        kafka = new KafkaContainer(IMAGE);
        kafka.start();
        try (Admin admin = admin()) {
            admin.createTopics(B0Topics.ALL.stream().map(t -> new NewTopic(t, B0Topics.PARTITIONS, (short) 1)).toList())
                    .all().get(30, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void stop() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    void 콘솔_producer가_넣은_레코드의_위치_순서_key_헤더_본문이_기대와_같고_설정이_적용됐다() throws Exception {
        B0Fixture fixture = B0FixtureGenerator.generate(B0FixtureGenerator.DEFAULT_SEED);
        Map<String, List<B0Record>> sample = fixture.allRecords().stream()
                .collect(Collectors.groupingBy(B0Record::topic, Collectors.collectingAndThen(Collectors.toList(),
                        list -> list.subList(0, Math.min(PER_TOPIC, list.size())))));
        kafka.copyFileToContainer(MountableFile.forHostPath(LOADTEST.resolve("producer.properties")),
                "/tmp/b0/producer.properties");
        kafka.copyFileToContainer(MountableFile.forHostPath(LOADTEST.resolve("producer-log4j2.yaml")),
                "/tmp/b0/producer-log4j2.yaml");

        for (Map.Entry<String, List<B0Record>> entry : sample.entrySet()) {
            String topic = entry.getKey();
            Path file = Files.createTempFile("b0-" + topic, ".txt");
            Files.writeString(file, entry.getValue().stream().map(B0Record::line)
                    .collect(Collectors.joining("\n", "", "\n")), StandardCharsets.UTF_8);
            kafka.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/b0/" + topic + ".txt");

            ExecResult result = kafka.execInContainer("bash", "-c", B0ConsoleProducer.command("localhost:9093",
                    topic, "/tmp/b0/producer.properties", "/tmp/b0/producer-log4j2.yaml", "/tmp/b0/" + topic + ".txt"));

            assertThat(result.getExitCode()).as(result.getStderr()).isZero();
            B0ConsoleProducer.assertConfigApplied(result.getStderr());
            assertThat(B0ConsoleProducer.errorLines(result.getStderr())).as(result.getStderr()).isEmpty();
        }

        for (Map.Entry<String, List<B0Record>> entry : sample.entrySet()) {
            assertWritten(entry.getKey(), entry.getValue());
        }
    }

    private static void assertWritten(String topic, List<B0Record> expected) {
        Map<Integer, List<B0Record>> expectedByPartition = new HashMap<>();
        for (B0Record record : expected) {
            expectedByPartition.computeIfAbsent(record.partition(), p -> new ArrayList<>()).add(record);
        }
        Map<Integer, List<ConsumerRecord<byte[], byte[]>>> actualByPartition = readAll(topic);

        assertThat(actualByPartition.keySet()).as(topic).isEqualTo(expectedByPartition.keySet());
        for (Map.Entry<Integer, List<B0Record>> partition : expectedByPartition.entrySet()) {
            List<ConsumerRecord<byte[], byte[]>> actual = actualByPartition.get(partition.getKey());
            List<B0Record> wanted = partition.getValue();
            assertThat(actual).as(topic + "-" + partition.getKey()).hasSameSizeAs(wanted);
            for (int i = 0; i < wanted.size(); i++) {
                ConsumerRecord<byte[], byte[]> got = actual.get(i);
                B0Record want = wanted.get(i);
                assertThat(got.offset()).isEqualTo(i);
                assertThat(got.key()).isEqualTo(want.key().getBytes(StandardCharsets.UTF_8));
                assertThat(got.value()).isEqualTo(want.value().getBytes(StandardCharsets.UTF_8));
                Header typeId = got.headers().lastHeader("__TypeId__");
                assertThat(typeId).isNotNull();
                assertThat(typeId.value()).isEqualTo(want.typeId().getBytes(StandardCharsets.UTF_8));
                assertThat(got.headers().toArray()).hasSize(1);
            }
        }
    }

    private static Map<Integer, List<ConsumerRecord<byte[], byte[]>>> readAll(String topic) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        Map<Integer, List<ConsumerRecord<byte[], byte[]>>> byPartition = new HashMap<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(config, new ByteArrayDeserializer(),
                new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (int p = 0; p < B0Topics.PARTITIONS; p++) {
                partitions.add(new TopicPartition(topic, p));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))
                    && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r ->
                        byPartition.computeIfAbsent(r.partition(), p -> new ArrayList<>()).add(r));
            }
        }
        return byPartition;
    }

    private static Admin admin() {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        return Admin.create(config);
    }
}
