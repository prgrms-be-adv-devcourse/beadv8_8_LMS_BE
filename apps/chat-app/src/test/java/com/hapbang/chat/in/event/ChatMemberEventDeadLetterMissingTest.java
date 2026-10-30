package com.hapbang.chat.in.event;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;

import com.hapbang.chat.app.ChatUserFacade;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 운영 공백 재현(회원 이벤트 Kafka 소비 테스트 세션에서 발견): DLT를 만들지 못했고 브로커 토픽 자동 생성이 꺼져 있으면, DLT로 보내야 하는 레코드가 DLT 발행 실패로
 * 커밋되지 않고 끝없이 다시 처리되어 같은 파티션의 다음 레코드가 막힌다. DLT를 만들면 그때 풀린다.
 * <p>
 * 공용 Kafka 컨테이너는 자동 생성이 켜져 있어(브로커 기본값) 이 상황을 만들 수 없으므로, 자동 생성을 끈 이 클래스 전용 브로커를 띄운다.
 * DLT 생성은 복제 수 3(브로커 1대)으로 실패시킨다. DLT 발행 한 번은 앱 설정({@code dead-letter-send-timeout})이 제한하므로
 * (없는 토픽의 메타데이터 대기 포함) 실패 주기도 그 안에 든다. 새 컨텍스트이므로 끝나면 닫는다.
 */
@IntegrationTest
@DirtiesContext
class ChatMemberEventDeadLetterMissingTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "member.events.v1.dlt-missing-" + SUFFIX;
    private static final String GROUP = "chat-app-dlt-missing-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(2);
    // 재처리 주기의 DLT 발행 외 비용(seek·poll·리스너 처리) 여유
    private static final Duration CYCLE_SLACK = Duration.ofSeconds(1);

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withLabel("hapbang.test", "tkt-417-dlt-missing");

    static {
        KAFKA.start();
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("원본 토픽을 만들지 못했습니다.", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("hapbang.chat.member-events.enabled", () -> "true");
        registry.add("hapbang.chat.member-events.topic", () -> TOPIC);
        registry.add("hapbang.chat.member-events.group-id", () -> GROUP);
        // 브로커 1대에 복제 3: KafkaAdmin의 DLT 생성이 실패한다(앱은 뜬다)
        registry.add("hapbang.chat.member-events.dead-letter-replicas", () -> "3");
        registry.add("hapbang.chat.member-events.dead-letter-send-timeout", () -> SEND_TIMEOUT.toSeconds() + "s");
    }

    @MockitoSpyBean
    private ChatUserFacade chatUserFacade;

    @Autowired
    private ChatUserRepository chatUserRepository;

    @Autowired
    @Qualifier("chatMemberEventDeadLetterTemplate")
    private KafkaTemplate<String, Object> deadLetterTemplate;

    @Test
    void DLT가_없고_자동_생성이_꺼져_있으면_DLT로_보낼_레코드가_끝없이_다시_처리되어_다음_레코드가_막히고_DLT를_만들면_풀린다()
            throws Exception {
        assertThat(autoCreateTopicsEnable()).isEqualTo("false");
        assertThat(topics()).contains(TOPIC).doesNotContain(DLT);

        long rejected = 400_001L;
        long next = 400_002L;
        List<Long> attempts = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            UserDto user = invocation.getArgument(0);
            if (user != null && user.id() == rejected) {
                attempts.add(System.nanoTime());
            }
            return invocation.callRealMethod();
        }).when(chatUserFacade).syncJoined(any());
        // 복제할 수 없는 회원 정보(닉네임 공백): 재시도 없이 바로 DLT로 보내야 하는 레코드
        RecordMetadata failing = joined(rejected, " ");
        joined(next, "다음회원");

        // DLT 발행이 실패할 때마다 같은 레코드를 다시 받는다(재시도 제외 예외라도 복구 실패면 반복)
        await().atMost(TIMEOUT).untilAsserted(
                () -> verify(chatUserFacade, atLeast(3)).syncJoined(argThat(user -> user.id() == rejected)));
        assertThat(committedOffset()).as("실패한 레코드를 넘는 offset을 커밋하지 않는다")
                .satisfiesAnyOf(offset -> assertThat(offset).isNull(),
                        offset -> assertThat(offset).isLessThanOrEqualTo(failing.offset()));
        assertThat(chatUserRepository.findById(next)).as("같은 파티션의 다음 레코드가 막힌다").isEmpty();
        verify(chatUserFacade, never()).syncJoined(argThat(user -> user.id() == next));
        assertThat(topics()).as("DLT 발행 시도가 토픽을 만들지 않는다(자동 생성 꺼짐)").doesNotContain(DLT);
        // 재처리 주기마다 DLT 발행 한 번이 설정 시간 안에 실패한다(운영 기본값이면 메타데이터 대기만 60초)
        List<Long> observed = List.copyOf(attempts);
        for (int i = 1; i < observed.size(); i++) {
            assertThat(Duration.ofNanos(observed.get(i) - observed.get(i - 1))).as("재처리 주기 %d", i)
                    .isLessThanOrEqualTo(SEND_TIMEOUT.plus(CYCLE_SLACK));
        }
        // DLT 발행 한 번(없는 토픽의 메타데이터 대기 포함)은 dead-letter-send-timeout 안에 실패로 끝난다
        long start = System.nanoTime();
        Throwable failure = catchThrowable(() -> deadLetterTemplate
                .send(DLT, "probe", "probe".getBytes(StandardCharsets.UTF_8)).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        assertThat(failure).as("DLT가 없으면 발행이 실패한다").isNotNull();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThanOrEqualTo(SEND_TIMEOUT);

        // 운영자가 DLT를 만들면 다음 주기에 DLT로 넘기고 offset이 넘어가 다음 레코드를 처리한다
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(DLT, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(next)).isPresent());
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(committedOffset()).isGreaterThan(failing.offset()));
        assertThat(deadLetterKeys()).contains(String.valueOf(rejected));
        assertThat(chatUserRepository.findById(rejected)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    private static Admin admin() {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        return Admin.create(config);
    }

    private static String autoCreateTopicsEnable() throws Exception {
        try (Admin admin = admin()) {
            Collection<Node> nodes = admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
            ConfigResource broker = new ConfigResource(ConfigResource.Type.BROKER,
                    String.valueOf(nodes.iterator().next().id()));
            Map<ConfigResource, Config> configs = admin.describeConfigs(List.of(broker)).all()
                    .get(10, TimeUnit.SECONDS);
            return configs.get(broker).get("auto.create.topics.enable").value();
        }
    }

    private static Set<String> topics() throws Exception {
        try (Admin admin = admin()) {
            return admin.listTopics().names().get(10, TimeUnit.SECONDS);
        }
    }

    private static Long committedOffset() throws Exception {
        try (Admin admin = admin()) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS).get(PARTITION);
            return committed == null ? null : committed.offset();
        }
    }

    private static Set<String> deadLetterKeys() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        Set<String> keys = new HashSet<>();
        TopicPartition dlt = new TopicPartition(DLT, 0);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new StringDeserializer())) {
            consumer.assign(List.of(dlt));
            consumer.seekToBeginning(List.of(dlt));
            long end = consumer.endOffsets(List.of(dlt)).get(dlt);
            await().atMost(TIMEOUT).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    keys.add(record.key());
                }
                return consumer.position(dlt) >= end;
            });
        }
        return keys;
    }

    private static RecordMetadata joined(long id, String nickname) {
        String json = "{\"user\":{\"id\":" + id + ",\"nickname\":\"" + nickname
                + "\",\"role\":\"INFLUENCER\",\"status\":\"ACTIVE\",\"version\":0}}";
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(config, new StringSerializer(),
                new ByteArraySerializer())) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, String.valueOf(id),
                    json.getBytes(StandardCharsets.UTF_8));
            record.headers().add("__TypeId__", UserJoinedEvent.class.getName().getBytes(StandardCharsets.UTF_8));
            return producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("회원 이벤트를 보내지 못했습니다.", e);
        }
    }
}
