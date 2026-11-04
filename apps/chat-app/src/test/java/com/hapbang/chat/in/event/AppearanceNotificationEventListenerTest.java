package com.hapbang.chat.in.event;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.domain.AppearanceApplicationSubmittedData;
import com.hapbang.chat.domain.AppearanceRequestSentData;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.NotificationKind;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.event.kafka.ChatKafkaConfig;
import com.hapbang.chat.in.event.kafka.ChatKafkaConstants;
import com.hapbang.chat.in.event.kafka.ChatKafkaConsumerSettings;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 출연 이벤트를 실제 Kafka(Testcontainers)로 받아 채팅 알림을 남기는지 검증한다(설계 문서 5.5).
 * <p>
 * 발행 앱의 형식대로 보낸다: key는 {@code appearance-request:<요청 ID>}·{@code appearance-application:<지원 ID>}, value는 shared
 * 레코드의 JSON 바이트, 헤더는 {@code __TypeId__}. 이 클래스만 쓰는 토픽·그룹을 UUID로 만들고 DLT는 규칙대로
 * {@code <토픽>.<그룹>.DLT}다. 파티션이 하나라 뒤에 보낸 레코드가 반영되면 앞 레코드의 처리도 끝났다.
 */
@IntegrationTest
// 이 클래스만 쓰는 토픽·그룹 설정의 컨텍스트라 끝나면 닫아 Kafka 소비자를 남기지 않는다
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, AppearanceNotificationEventListenerTest.Topics.class})
class AppearanceNotificationEventListenerTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "appearance.events.v1.test-" + SUFFIX;
    private static final String GROUP = "chat-app-appearance-test-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final AtomicLong NEXT_ID = new AtomicLong(500_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(ChatKafkaConstants.APPEARANCE_EVENTS_ENABLED, () -> "true");
    }

    /** 이 클래스 전용 토픽·그룹·DLT. 재시도는 1회·첫 간격 100ms로 줄인다(운영은 3회·1초) */
    @TestBean(name = ChatKafkaConfig.APPEARANCE_EVENT_SETTINGS)
    private ChatKafkaConsumerSettings appearanceEventSettings;

    static ChatKafkaConsumerSettings appearanceEventSettings() {
        return new ChatKafkaConsumerSettings(TOPIC, GROUP, DLT, 1, Duration.ofMillis(100),
                ChatKafkaConstants.RETRY_MAX_INTERVAL, ChatKafkaConstants.DEAD_LETTER_SEND_TIMEOUT,
                ChatKafkaConstants.DEAD_LETTER_PARTITIONS, ChatKafkaConstants.DEAD_LETTER_REPLICAS);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    private KafkaProducer<String, byte[]> producer;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
    }

    @AfterEach
    void tearDown() {
        producer.close(Duration.ofSeconds(5));
    }

    @Test
    void 출연_요청_도착_이벤트를_받으면_게스트와의_방에_알림이_남는다() {
        long requestId = nextId();

        requestSent(requestId);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notification(NotificationKind.APPEARANCE_REQUEST_SENT,
                requestId)).hasValueSatisfying(message -> {
                    assertThat(message.getSenderUserId()).isEqualTo(HOST_ID);
                    // 서버는 문구를 만들지 않고, 이벤트 값과 채팅 회원 복제본의 닉네임만 KST 일시로 남긴다
                    assertThat(message.getContent()).isEmpty();
                    assertThat(message.readAppearanceRequestSent()).isEqualTo(new AppearanceRequestSentData(1, "호스트",
                            "금요일 밤 합방", 50_000L, "2026-11-20T21:00:00", "2026-11-13T21:00:00"));
                }));
    }

    @Test
    void 출연_모집_지원_도착_이벤트를_받으면_호스트와의_방에_알림이_남는다() {
        long applicationId = nextId();

        send(AppearanceApplicationSubmittedEvent.class.getName(), "appearance-application:" + applicationId, """
                {"application":{"applicationId":%d,"recruitId":7,"recruitTitle":"주말 게임 합방 모집",
                 "hostId":%d,"guestId":%d,"appliedAt":"2026-11-01T10:00:00"}}""".formatted(applicationId, HOST_ID,
                GUEST_ID));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notification(
                NotificationKind.APPEARANCE_APPLICATION_SUBMITTED, applicationId)).hasValueSatisfying(message -> {
                    assertThat(message.getSenderUserId()).isEqualTo(GUEST_ID);
                    assertThat(message.getParentReferenceId()).isEqualTo(7L);
                    assertThat(message.getContent()).isEmpty();
                    assertThat(message.readAppearanceApplicationSubmitted())
                            .isEqualTo(new AppearanceApplicationSubmittedData(1, "게스트", "주말 게임 합방 모집"));
                }));
    }

    @Test
    void 같은_이벤트를_두_번_받아도_알림은_하나다() {
        long requestId = nextId();
        long next = nextId();

        requestSent(requestId);
        requestSent(requestId);
        requestSent(next);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notification(NotificationKind.APPEARANCE_REQUEST_SENT,
                next)).isPresent());
        assertThat(chatMessageRepository.findAll()).filteredOn(message -> Long.valueOf(requestId)
                .equals(message.getReferenceId())).hasSize(1);
        assertThat(deadLetterKeys()).doesNotContain("appearance-request:" + requestId);
    }

    @Test
    void 깨진_메시지는_재시도_없이_DLT로_보내고_뒤_레코드를_처리한다() {
        long broken = nextId();
        long next = nextId();

        send(AppearanceRequestSentEvent.class.getName(), "appearance-request:" + broken, "{not-json");
        requestSent(next);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notification(NotificationKind.APPEARANCE_REQUEST_SENT,
                next)).isPresent());
        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter("appearance-request:" + broken);
        assertThat(exceptionClasses(deadLetter)).contains("DeserializationException");
        assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).isEqualTo("{not-json");
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
    }

    @Test
    void 알림에_필요한_값이_없으면_재시도_없이_DLT로_보낸다() {
        long missing = nextId();

        send(AppearanceRequestSentEvent.class.getName(), "appearance-request:" + missing,
                "{\"request\":{\"requestId\":%d,\"hostId\":%d,\"fee\":1}}".formatted(missing, HOST_ID));

        assertThat(exceptionClasses(awaitDeadLetter("appearance-request:" + missing)))
                .contains("RejectedEventException");
        assertThat(notification(NotificationKind.APPEARANCE_REQUEST_SENT, missing)).isEmpty();
    }

    @Test
    void 처리하지_않는_출연_이벤트_종류는_건너뛰고_DLT로_보내지_않는다() {
        long cancel = nextId();
        long next = nextId();

        send(AppearanceCancelRequestedEvent.class.getName(), "appearance:" + cancel, "{\"cancelRequest\":null}");
        requestSent(next);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notification(NotificationKind.APPEARANCE_REQUEST_SENT,
                next)).isPresent());
        assertThat(deadLetterKeys()).doesNotContain("appearance:" + cancel);
    }

    // ---------------------------------------------------------------------------------------------

    private void requestSent(long requestId) {
        send(AppearanceRequestSentEvent.class.getName(), "appearance-request:" + requestId, """
                {"request":{"requestId":%d,"hostId":%d,"guestId":%d,"title":"금요일 밤 합방","fee":50000,
                 "scheduledAt":"2026-11-20T21:00:00","acceptDeadline":"2026-11-13T21:00:00"}}""".formatted(requestId,
                HOST_ID, GUEST_ID));
    }

    private void send(String typeId, String key, String json) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, key, json.getBytes(StandardCharsets.UTF_8));
        record.headers().add("__TypeId__", typeId.getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("출연 이벤트를 보내지 못했습니다.", e);
        }
    }

    private java.util.Optional<ChatMessage> notification(NotificationKind kind, long referenceId) {
        return chatMessageRepository.findAll().stream()
                .filter(message -> message.getNotificationKind() == kind
                        && Long.valueOf(referenceId).equals(message.getReferenceId()))
                .findFirst();
    }

    private ConsumerRecord<String, byte[]> awaitDeadLetter(String key) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = deadLetterConsumer()) {
            await().atMost(TIMEOUT).until(() -> {
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

    private List<String> deadLetterKeys() {
        List<String> keys = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = deadLetterConsumer()) {
            for (int i = 0; i < 5; i++) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> keys.add(record.key()));
            }
        }
        return keys;
    }

    private KafkaConsumer<String, byte[]> deadLetterConsumer() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer());
        consumer.subscribe(List.of(DLT));
        return consumer;
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String exceptionClasses(ConsumerRecord<?, ?> record) {
        return header(record, KafkaHeaders.DLT_EXCEPTION_FQCN) + " "
                + header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    /** 이 클래스 전용 원본 토픽. DLT는 chat-app 설정이 만든다 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic appearanceEventTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
