package com.hapbang.customersupport.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.customersupport.app.InquiryUseCase;
import com.hapbang.customersupport.app.LectureObjectionResultUseCase;
import com.hapbang.customersupport.domain.HpReturnStatus;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.ObjectionLinkStatus;
import com.hapbang.customersupport.fixture.LectureObjectionFixture;
import com.hapbang.customersupport.global.config.CustomerSupportEventExternalizationConfig;
import com.hapbang.customersupport.global.config.CustomerSupportKafkaConfig;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.customersupport.fixture.LectureObjectionResults;
import com.hapbang.shared.customersupport.event.CustomerSupportEventTopic;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.lecture.event.CartItemChangedEvent;
import com.hapbang.shared.lecture.event.LectureChangedEvent;
import com.hapbang.shared.lecture.event.LectureEventTopic;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LecturePurchaseChangedEvent;
import com.hapbang.shared.lecture.event.LectureReviewChangedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import com.hapbang.testsupport.RecordedSpans;
import com.hapbang.testsupport.TraceRequests;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * 판단 요청과 알림용 답변 도착·접수 불가 이벤트가 {@code customersupport.events.v1}에 key {@code inquiry:<문의 ID>}로 나가고,
 * 강의가 {@code lecture.events.v1}에 보낸 결과·반환 이벤트가 문의에 기록되는지 실제 Kafka로 확인한다 (#279).
 * 테스트마다 다른 주문 항목·문의를 쓰고 자기 key의 메시지만 본다. 외부화와 리스너를 켠 설정이라 컨텍스트를 따로 띄우고 끝나면 닫는다.
 */
@IntegrationTest
@Import({KafkaTestcontainersConfig.class, CustomerSupportObjectionKafkaTest.LectureTopicConfig.class,
        RecordedSpans.Config.class})
// trace 확인(#630)은 부모 없이 시작한 span도 놓치지 않게 샘플링 비율을 1로 둔다
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true",
        "spring.modulith.events.externalization.enabled=true", "management.tracing.sampling.probability=1.0"})
@DirtiesContext
class CustomerSupportObjectionKafkaTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 9L;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration HOLD = Duration.ofMillis(800);

    /** 강의 앱이 선언하는 업무 토픽을 대신 만든다. 고객센터 앱은 이 토픽의 DLT만 선언한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class LectureTopicConfig {

        @Bean
        NewTopic lectureEventsTopic() {
            return TopicBuilder.name(LectureEventTopic.NAME).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private InquiryUseCase inquiryUseCase;

    @Autowired
    private LectureObjectionResultUseCase lectureObjectionResultUseCase;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private RecordedSpans spans;

    private KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        Map<String, Object> properties = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "customer-support-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(CustomerSupportEventTopic.NAME));
        await().atMost(TIMEOUT).pollInSameThread().until(() -> {
            consumer.poll(Duration.ofMillis(100));
            return !consumer.assignment().isEmpty();
        });
        consumer.seekToEnd(consumer.assignment());
        consumer.assignment().forEach(consumer::position);
    }

    @AfterEach
    void tearDown() {
        consumer.close();
        received.clear();
    }

    @Test
    void 판단_요청은_커밋_뒤_고객센터_토픽에_나가고_강의_결과를_받으면_문의에_기록한다() {
        long orderItemId = 801L;
        Long inquiryId = LectureObjectionFixture.filed(transactionTemplate, inquiryRepository, USER_ID, orderItemId);

        inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다.");

        ConsumerRecord<String, String> record = awaitExactly("inquiry:" + inquiryId, 1).getFirst();
        assertThat(header(record, CustomerSupportEventExternalizationConfig.EVENT_TYPE))
                .isEqualTo(CustomerSupportEventExternalizationConfig.RESOLUTION_REQUESTED);
        assertThat(header(record, CustomerSupportEventExternalizationConfig.EVENT_VERSION)).isEqualTo("1");
        assertThat(record.value()).contains("\"commandId\":\"RESOLVE:" + inquiryId + "\"", "\"accepted\":true");

        send(orderItemId, LectureObjectionResults.resolved(inquiryId, orderItemId, true));
        send(orderItemId, LectureObjectionResults.returnCompleted(inquiryId, orderItemId));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(inquiryRepository.findById(inquiryId))
                .hasValueSatisfying(inquiry -> {
                    assertThat(inquiry.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLVED);
                    assertThat(inquiry.getHpReturnStatus()).isEqualTo(HpReturnStatus.COMPLETED);
                }));
        // 판단 안내 답변은 답변 도착 이벤트를 내지 않는다(판단 알림은 강의 결과 이벤트로 간다)
        awaitExactly("inquiry:" + inquiryId, 1);
    }

    @Test
    void 관리자_답변은_답변_도착_이벤트로_고객센터_토픽에_나간다() {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.SERVICE_COMPLAINT, "환전이 안 돼요", "내용", null)
                .getId();

        Long replyId = inquiryUseCase.reply(inquiryId, ADMIN_ID, "확인했습니다.").getReplies().getLast().getId();

        ConsumerRecord<String, String> record = awaitExactly("inquiry:" + inquiryId, 1).getFirst();
        assertThat(header(record, CustomerSupportEventExternalizationConfig.EVENT_TYPE))
                .isEqualTo(CustomerSupportEventExternalizationConfig.INQUIRY_ANSWERED);
        assertThat(header(record, CustomerSupportEventExternalizationConfig.EVENT_VERSION)).isEqualTo("1");
        assertThat(record.value()).contains("\"inquiryId\":" + inquiryId, "\"replyId\":" + replyId,
                "\"userId\":" + USER_ID, "\"title\":\"환전이 안 돼요\"", "\"category\":\"SERVICE_COMPLAINT\"",
                "\"answeredAt\":");
    }

    @Test
    void 재시도_배치의_접수_불가는_접수_불가_이벤트로_고객센터_토픽에_나간다() {
        long orderItemId = 807L;
        Long inquiryId = LectureObjectionFixture.filing(transactionTemplate, inquiryRepository, USER_ID, orderItemId);

        lectureObjectionResultUseCase.recordFileRejected(inquiryId, LectureObjectionFailureCode.OBJECTION_PERIOD_EXPIRED,
                false);

        ConsumerRecord<String, String> record = awaitExactly("inquiry:" + inquiryId, 1).getFirst();
        assertThat(header(record, CustomerSupportEventExternalizationConfig.EVENT_TYPE))
                .isEqualTo(CustomerSupportEventExternalizationConfig.FILE_REJECTED);
        assertThat(header(record, CustomerSupportEventExternalizationConfig.EVENT_VERSION)).isEqualTo("1");
        assertThat(record.value()).contains("\"inquiryId\":" + inquiryId, "\"orderItemId\":" + orderItemId,
                "\"buyerUserId\":" + USER_ID, "\"fileRejectedAt\":");
    }

    // trace(#630): 관리자 요청 안에서 보낸 판단 요청은 커밋 뒤 다른 스레드에서 나가도 요청과 같은 trace를 싣고,
    // 강의가 그 trace로 보낸 결과도 같은 trace에서 처리한다
    @Test
    void 판단_요청과_강의_결과_처리가_관리자_요청과_같은_trace에_남는다() throws Exception {
        long orderItemId = 803L;
        Long inquiryId = LectureObjectionFixture.filed(transactionTemplate, inquiryRepository, USER_ID, orderItemId);

        // 관리자 요청 하나(HTTP server span)를 대신한다
        Observation.createNotStarted("objection-resolve-request", observationRegistry)
                .observe(() -> inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다."));

        ConsumerRecord<String, String> record = awaitExactly("inquiry:" + inquiryId, 1).getFirst();
        RecordedSpans.await(() -> !spans.named("objection-resolve-request").isEmpty());
        String traceId = spans.named("objection-resolve-request").getFirst().getTraceId();
        RecordedSpans.await(() -> spans.all().stream()
                .anyMatch(span -> span.getTraceId().equals(traceId) && span.getKind() == SpanKind.PRODUCER));
        SpanData sent = spans.all().stream()
                .filter(span -> span.getTraceId().equals(traceId) && span.getKind() == SpanKind.PRODUCER)
                .findFirst().orElseThrow();
        assertThat(header(record, "traceparent")).startsWith("00-" + traceId + "-" + sent.getSpanId() + "-");

        // 강의 앱이 받은 traceparent를 이어 결과를 보낸 것처럼 보낸다
        String lectureSpanId = TraceRequests.randomSpanId();
        send(orderItemId, LectureObjectionResults.resolved(inquiryId, orderItemId, true),
                TraceRequests.traceparent(traceId, lectureSpanId, true));
        RecordedSpans.await(() -> spans.all().stream()
                .anyMatch(span -> span.getTraceId().equals(traceId) && span.getKind() == SpanKind.CONSUMER));
        assertThat(spans.all()).filteredOn(span -> span.getTraceId().equals(traceId) && span.getKind() == SpanKind.CONSUMER)
                .extracting(SpanData::getParentSpanId).containsOnly(lectureSpanId);
    }

    @Test
    void 고객센터_토픽과_강의_결과_DLT는_partition_3개로_선언한다() {
        String deadLetter = CustomerSupportKafkaConfig.deadLetterTopic(LectureEventTopic.NAME,
                CustomerSupportKafkaConfig.OBJECTION_RESULT_GROUP);
        assertThat(kafkaAdmin.describeTopics(CustomerSupportEventTopic.NAME, deadLetter))
                .allSatisfy((name, description) -> assertThat(description.partitions()).hasSize(3));
    }

    /**
     * 깨진 강의 이벤트는 재시도 없이 DLT로 가고, 그 전송이 지표에 잡힌다 (#339).
     * {@code eventType} 헤더 없이 보내 {@code __TypeId__}로 찾는 경로도 함께 지난다(#722)
     */
    @Test
    void 읽을_수_없는_강의_이벤트는_DLT로_보내고_전송_지표에_센다() {
        sendRaw("order-item:802", "{not json", Map.of("__TypeId__", LectureObjectionResolvedEvent.class.getName()));

        assertDeadLetterSendCounted();
    }

    /**
     * 강의가 같은 토픽에 내보내는 AI 추천용 동기화 이벤트(#712)는 고객센터가 받지 않는 타입이다. 무시용 레코드로 읽어 커밋해,
     * 재시도·DLT 없이 같은 partition의 다음 결과를 처리한다. 클래스를 찾지 않으므로 native 이미지에 등록하지 않아도 같다(#722)
     */
    @Test
    void 강의_동기화_이벤트는_무시하고_DLT로_보내지_않고_다음_결과를_처리한다() {
        long orderItemId = 804L;
        Long inquiryId = LectureObjectionFixture.filed(transactionTemplate, inquiryRepository, USER_ID, orderItemId);
        inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다.");
        OffsetDateTime at = OffsetDateTime.parse("2026-11-06T09:00:00+09:00");
        try (KafkaConsumer<String, String> deadLetters = consumerAtEnd(objectionResultDeadLetter())) {
            // 같은 key로 보내 같은 partition에서 결과보다 먼저 읽히게 한다
            send(orderItemId, new LectureChangedEvent(new LectureSyncDto(11L, 3L, "강의", null, 10_000L, true, false,
                    List.of(new LectureContentSyncDto(21L, "1강", 1)), 1L)));
            send(orderItemId, new LecturePurchaseChangedEvent(new LecturePurchaseSyncDto(orderItemId, USER_ID, 11L,
                    LecturePurchaseResult.PENDING, at, at, 2L)));
            send(orderItemId, new CartItemChangedEvent(new CartItemSyncDto(31L, USER_ID, 11L, true, at, 1L)));
            send(orderItemId, new LectureReviewChangedEvent(new LectureReviewSyncDto(41L, 11L, USER_ID, 5, false,
                    false, 1L)));
            send(orderItemId, LectureObjectionResults.resolved(inquiryId, orderItemId, true));

            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(inquiryRepository.findById(inquiryId))
                    .hasValueSatisfying(inquiry ->
                            assertThat(inquiry.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLVED)));
            assertNoDeadLetter(deadLetters, "order-item:" + orderItemId);
        }
    }

    /**
     * 고객센터 앱에 클래스가 없는 타입(native 이미지에 등록하지 않은 새 이벤트와 같은 상황)도 {@code eventType}만 보고 무시한다 (#722).
     * {@code __TypeId__}로 클래스를 찾으면 역직렬화에 실패해 재시도 없이 DLT로 간다
     */
    @Test
    void 클래스가_없는_모르는_타입은_DLT로_보내지_않고_다음_결과를_처리한다() {
        long orderItemId = 805L;
        Long inquiryId = LectureObjectionFixture.filed(transactionTemplate, inquiryRepository, USER_ID, orderItemId);
        inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다.");
        try (KafkaConsumer<String, String> deadLetters = consumerAtEnd(objectionResultDeadLetter())) {
            sendRaw("order-item:" + orderItemId, "{\"lectureId\":11}", Map.of(
                    "__TypeId__", "com.hapbang.shared.lecture.event.NotYetKnownEvent",
                    CustomerSupportEventExternalizationConfig.EVENT_TYPE, "NotYetKnownEvent"));
            send(orderItemId, LectureObjectionResults.resolved(inquiryId, orderItemId, true));

            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(inquiryRepository.findById(inquiryId))
                    .hasValueSatisfying(inquiry ->
                            assertThat(inquiry.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLVED)));
            assertNoDeadLetter(deadLetters, "order-item:" + orderItemId);
        }
    }

    /**
     * 강의가 알림용 값(받는 사람 ID·강의 제목·사건 시각·반환 지연 회차, #818)을 더하기 전 형식의 결과도 그대로 처리한다.
     * 강의 앱이 먼저 배포되기 전에 보낸 메시지와, 배포 전에 저장된 발행 기록을 다시 보낸 메시지가 이 형식이다. 새 필드는 null로 읽힌다
     */
    @Test
    void 새_필드가_없는_옛_형식_강의_결과도_문의에_기록한다() {
        long orderItemId = 806L;
        Long inquiryId = LectureObjectionFixture.filed(transactionTemplate, inquiryRepository, USER_ID, orderItemId);
        inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다.");
        String key = "order-item:" + orderItemId;
        try (KafkaConsumer<String, String> deadLetters = consumerAtEnd(objectionResultDeadLetter())) {
            sendRaw(key, """
                    {"commandId":"RESOLVE:%d","inquiryId":%d,"orderItemId":%d,"accepted":true}
                    """.formatted(inquiryId, inquiryId, orderItemId),
                    Map.of(CustomerSupportEventExternalizationConfig.EVENT_TYPE, "LectureObjectionResolvedEvent"));
            sendRaw(key, """
                    {"inquiryId":%d,"orderItemId":%d}
                    """.formatted(inquiryId, orderItemId),
                    Map.of(CustomerSupportEventExternalizationConfig.EVENT_TYPE,
                            "LectureObjectionReturnActionRequiredEvent"));
            sendRaw(key, """
                    {"inquiryId":%d,"orderItemId":%d}
                    """.formatted(inquiryId, orderItemId),
                    Map.of(CustomerSupportEventExternalizationConfig.EVENT_TYPE, "LectureObjectionReturnCompletedEvent"));

            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(inquiryRepository.findById(inquiryId))
                    .hasValueSatisfying(inquiry -> {
                        assertThat(inquiry.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLVED);
                        assertThat(inquiry.getHpReturnStatus()).isEqualTo(HpReturnStatus.COMPLETED);
                    }));
            assertNoDeadLetter(deadLetters, key);
        }
    }

    private static String objectionResultDeadLetter() {
        return CustomerSupportKafkaConfig.deadLetterTopic(LectureEventTopic.NAME,
                CustomerSupportKafkaConfig.OBJECTION_RESULT_GROUP);
    }

    /** 잠시 기다려도 이 key의 메시지가 DLT에 오지 않는다 */
    private static void assertNoDeadLetter(KafkaConsumer<String, String> deadLetters, String key) {
        List<ConsumerRecord<String, String>> deadLettered = new CopyOnWriteArrayList<>();
        await().during(HOLD).atMost(TIMEOUT).pollInSameThread().untilAsserted(() -> {
            deadLetters.poll(Duration.ofMillis(200)).forEach(deadLettered::add);
            assertThat(deadLettered).noneMatch(record -> key.equals(record.key()));
        });
    }

    /** 본문과 헤더를 그대로 실어 보낸다. 강의 앱이 아직 만들지 않은 타입이나 깨진 본문을 흉내 낸다 */
    private void sendRaw(String key, String body, Map<String, String> headers) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(LectureEventTopic.NAME, key,
                body.getBytes(StandardCharsets.UTF_8));
        headers.forEach((name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
        try (Producer<String, byte[]> producer = new KafkaProducer<>(Map.copyOf(kafkaAdmin.getConfigurationProperties()),
                new StringSerializer(), new ByteArraySerializer())) {
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 이 토픽을 읽는 소비자. 파티션을 받을 때까지 읽은 뒤 끝으로 옮겨, 이후 이 테스트가 만든 메시지만 받는다 */
    private KafkaConsumer<String, String> consumerAtEnd(String topic) {
        Map<String, Object> properties = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "customer-support-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        KafkaConsumer<String, String> topicConsumer = new KafkaConsumer<>(properties, new StringDeserializer(),
                new StringDeserializer());
        topicConsumer.subscribe(List.of(topic));
        await().atMost(TIMEOUT).pollInSameThread().until(() -> {
            topicConsumer.poll(Duration.ofMillis(100));
            return !topicConsumer.assignment().isEmpty();
        });
        topicConsumer.seekToEnd(topicConsumer.assignment());
        topicConsumer.assignment().forEach(topicConsumer::position);
        return topicConsumer;
    }

    /**
     * 강의 앱처럼 레코드를 JSON으로 싣고 클래스 이름을 타입 헤더({@code __TypeId__})로, 레코드 이름을 {@code eventType} 헤더로 붙인다.
     * 강의 앱의 {@code eventType} 값은 지금 레코드 이름과 같다(LectureEventExternalizationConfig)
     */
    private void send(long orderItemId, Object event) {
        send(orderItemId, event, null);
    }

    /** {@code traceparent}(없으면 null)를 헤더에 실어, trace를 계측한 강의 앱이 보낸 것처럼 보낸다 (#630) */
    private void send(long orderItemId, Object event, String traceparent) {
        try (Producer<String, Object> producer = new KafkaProducer<>(Map.copyOf(kafkaAdmin.getConfigurationProperties()),
                new StringSerializer(), new JacksonJsonSerializer<>())) {
            ProducerRecord<String, Object> record = new ProducerRecord<>(LectureEventTopic.NAME,
                    "order-item:" + orderItemId, event);
            record.headers().add(CustomerSupportEventExternalizationConfig.EVENT_TYPE,
                    event.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
            if (traceparent != null) {
                record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            }
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ConsumerRecord<String, String>> awaitExactly(String key, int count) {
        await().atMost(TIMEOUT).pollInSameThread()
                .untilAsserted(() -> assertThat(records(key)).hasSizeGreaterThanOrEqualTo(count));
        await().during(HOLD).atMost(TIMEOUT).pollInSameThread()
                .untilAsserted(() -> assertThat(records(key)).hasSize(count));
        return records(key);
    }

    private List<ConsumerRecord<String, String>> records(String key) {
        consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        return received.stream().filter(record -> key.equals(record.key())).toList();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** DLT 전송도 Kafka producer 지표로 센다 (#339). 토픽별 지표는 Micrometer가 60초마다 새로 등록하므로 바로 생기는 client 지표로 본다. */
    private void assertDeadLetterSendCounted() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(meterRegistry
                .find("kafka.producer.record.send.total").functionCounters())
                .filteredOn(counter -> String.valueOf(counter.getId().getTag("spring.id"))
                        .startsWith(CustomerSupportKafkaConfig.DEAD_LETTER_PRODUCER_FACTORY + "."))
                .extracting(FunctionCounter::count)
                .isNotEmpty()
                .allSatisfy(count -> assertThat(count).isGreaterThanOrEqualTo(1)));
    }
}
