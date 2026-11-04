package com.hapbang.customersupport.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import com.hapbang.customersupport.domain.HpReturnStatus;
import com.hapbang.customersupport.domain.ObjectionLinkStatus;
import com.hapbang.customersupport.fixture.LectureObjectionFixture;
import com.hapbang.customersupport.global.config.CustomerSupportEventExternalizationConfig;
import com.hapbang.customersupport.global.config.CustomerSupportKafkaConfig;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.shared.customersupport.event.CustomerSupportEventTopic;
import com.hapbang.shared.lecture.event.LectureEventTopic;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
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
 * 판단 요청이 {@code customersupport.events.v1}에 key {@code inquiry:<문의 ID>}로 나가고, 강의가 {@code lecture.events.v1}에
 * 보낸 결과·반환 이벤트가 문의에 기록되는지 실제 Kafka로 확인한다 (#279).
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

        send(orderItemId, new LectureObjectionResolvedEvent("RESOLVE:" + inquiryId, inquiryId, orderItemId, true));
        send(orderItemId, new LectureObjectionReturnCompletedEvent(inquiryId, orderItemId));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(inquiryRepository.findById(inquiryId))
                .hasValueSatisfying(inquiry -> {
                    assertThat(inquiry.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLVED);
                    assertThat(inquiry.getHpReturnStatus()).isEqualTo(HpReturnStatus.COMPLETED);
                }));
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
        send(orderItemId, new LectureObjectionResolvedEvent("RESOLVE:" + inquiryId, inquiryId, orderItemId, true),
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

    /** 깨진 강의 이벤트는 재시도 없이 DLT로 가고, 그 전송이 지표에 잡힌다 (#339) */
    @Test
    void 읽을_수_없는_강의_이벤트는_DLT로_보내고_전송_지표에_센다() {
        ProducerRecord<String, byte[]> broken = new ProducerRecord<>(LectureEventTopic.NAME, "order-item:802",
                "{not json".getBytes(StandardCharsets.UTF_8));
        broken.headers().add("__TypeId__",
                LectureObjectionResolvedEvent.class.getName().getBytes(StandardCharsets.UTF_8));
        try (Producer<String, byte[]> producer = new KafkaProducer<>(Map.copyOf(kafkaAdmin.getConfigurationProperties()),
                new StringSerializer(), new ByteArraySerializer())) {
            producer.send(broken).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertDeadLetterSendCounted();
    }

    /** 강의 앱처럼 레코드를 JSON으로 싣고 클래스 이름을 타입 헤더({@code __TypeId__})로 붙인다. */
    private void send(long orderItemId, Object event) {
        send(orderItemId, event, null);
    }

    /** {@code traceparent}(없으면 null)를 헤더에 실어, trace를 계측한 강의 앱이 보낸 것처럼 보낸다 (#630) */
    private void send(long orderItemId, Object event, String traceparent) {
        try (Producer<String, Object> producer = new KafkaProducer<>(Map.copyOf(kafkaAdmin.getConfigurationProperties()),
                new StringSerializer(), new JacksonJsonSerializer<>())) {
            ProducerRecord<String, Object> record = new ProducerRecord<>(LectureEventTopic.NAME,
                    "order-item:" + orderItemId, event);
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
