package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
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
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.lecture.app.LectureObjectionFacade;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureEventExternalizationConfig;
import com.hapbang.lecture.config.LectureKafkaConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureObjectionCommandRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.customersupport.event.CustomerSupportEventTopic;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.event.LectureEventTopic;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import com.hapbang.testsupport.RecordedSpans;
import com.hapbang.testsupport.TraceRequests;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * 고객센터가 {@code customersupport.events.v1}에 보낸 판단·철회 요청을 강의가 받아 항목에 반영하고, 결과를
 * {@code lecture.events.v1}에 key {@code order-item:<주문 항목 ID>}로 내보내는지 실제 Kafka로 확인한다 (#278).
 * <ul>
 *     <li>고객센터 앱처럼 shared 레코드를 JSON(타입 헤더 포함)으로, key는 {@code inquiry:<문의 ID>}로 보낸다.</li>
 *     <li>컨텍스트·소비 그룹·토픽을 테스트끼리 공유하므로 테스트마다 다른 문의 ID를 쓰고, 자기 주문 항목 key의 메시지만 본다.</li>
 *     <li>외부화와 리스너를 켠 설정이라 컨텍스트를 따로 띄우고, 끝나면 닫는다.</li>
 * </ul>
 */
@IntegrationTest
@Import({KafkaTestcontainersConfig.class, LectureObjectionKafkaTest.CustomerSupportTopicConfig.class,
        RecordedSpans.Config.class})
// trace 확인(#630)은 샘플링 비율을 1로 둬서 부모 없이 시작한 span도 놓치지 않게 한다
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true",
        "spring.modulith.events.externalization.enabled=true", "management.tracing.sampling.probability=1.0"})
@WithMockUser(roles = "INTERNAL")
@DirtiesContext
class LectureObjectionKafkaTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration HOLD = Duration.ofMillis(800);
    private static final String TRACEPARENT = "traceparent";
    private static final String OBJECTION_COMMAND_DLT = LectureKafkaConfig.deadLetterTopic(CustomerSupportEventTopic.NAME,
            LectureKafkaConfig.OBJECTION_COMMAND_GROUP);

    /** 고객센터 앱이 선언하는 업무 토픽을 대신 만든다. 강의 앱은 이 토픽의 DLT만 선언한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CustomerSupportTopicConfig {

        @Bean
        NewTopic customerSupportEventsTopic() {
            return TopicBuilder.name(CustomerSupportEventTopic.NAME).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private KafkaAdmin kafkaAdmin;

    // 재시도 trace 확인에서 첫 처리만 실패시킨다. 정하지 않으면 실제 메서드를 부른다
    @MockitoSpyBean
    private LectureObjectionFacade lectureObjectionFacade;

    @Autowired
    private RecordedSpans spans;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureObjectionCommandRepository lectureObjectionCommandRepository;

    private KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        consumer = consumerAtEnd(LectureEventTopic.NAME);
        spans.reset();
    }

    @AfterEach
    void tearDown() {
        consumer.close();
        received.clear();
    }

    /** 이 토픽을 읽는 소비자. 파티션을 받을 때까지 읽은 뒤 끝으로 옮겨, 이후 이 테스트가 만든 메시지만 받는다 */
    private KafkaConsumer<String, String> consumerAtEnd(String topic) {
        Map<String, Object> properties = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "lecture-test-" + UUID.randomUUID());
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

    @Test
    void 판단_요청을_받으면_항목에_반영하고_판단_결과를_강의_토픽에_내보낸다() {
        long inquiryId = 701L;
        Long itemId = filedItem(inquiryId);

        send(inquiryId, new LectureObjectionResolutionRequestedEvent("RESOLVE:" + inquiryId, inquiryId, itemId, false,
                ADMIN_ID));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureOrderRepository.findItemOfBuyer(itemId, BUYER_ID))
                .hasValueSatisfying(item -> assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.REJECTED)));
        ConsumerRecord<String, String> record = awaitExactly("order-item:" + itemId, 1).getFirst();
        assertThat(header(record, LectureEventExternalizationConfig.EVENT_TYPE))
                .isEqualTo(LectureEventExternalizationConfig.OBJECTION_RESOLVED);
        assertThat(header(record, LectureEventExternalizationConfig.EVENT_VERSION)).isEqualTo("1");
        assertThat(record.value()).contains("\"commandId\":\"RESOLVE:" + inquiryId + "\"", "\"accepted\":false");
    }

    @Test
    void 같은_철회_요청이_두_번_와도_한_번만_반영하고_결과도_한_번만_나간다() {
        long inquiryId = 702L;
        Long itemId = filedItem(inquiryId);
        LectureObjectionWithdrawalRequestedEvent withdraw = new LectureObjectionWithdrawalRequestedEvent(
                "WITHDRAW:" + inquiryId, inquiryId, itemId, BUYER_ID);

        send(inquiryId, withdraw);
        send(inquiryId, withdraw);

        ConsumerRecord<String, String> record = awaitExactly("order-item:" + itemId, 1).getFirst();
        assertThat(header(record, LectureEventExternalizationConfig.EVENT_TYPE))
                .isEqualTo(LectureEventExternalizationConfig.OBJECTION_WITHDRAWN);
        assertThat(lectureOrderRepository.findItemOfBuyer(itemId, BUYER_ID))
                .hasValueSatisfying(item -> assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.NONE));
        assertThat(lectureObjectionCommandRepository.findByCommandId(withdraw.commandId())).isPresent();
    }

    // trace(#630): 소비는 메시지 헤더의 traceparent를 부모로 삼고, 커밋 뒤 다른 스레드(Modulith 외부화)에서 보내는 결과에도
    // 같은 trace를 싣는다. 받는 앱은 그 헤더로 다시 이어 간다
    @Test
    void 판단_요청의_trace를_이어받아_처리하고_내보낸_결과에도_같은_trace를_싣는다() throws Exception {
        long inquiryId = 703L;
        Long itemId = filedItem(inquiryId);
        String traceId = TraceRequests.randomTraceId();
        String parentSpanId = TraceRequests.randomSpanId();

        send(inquiryId, new LectureObjectionResolutionRequestedEvent("RESOLVE:" + inquiryId, inquiryId, itemId, false,
                ADMIN_ID), TraceRequests.traceparent(traceId, parentSpanId, true));

        ConsumerRecord<String, String> record = awaitExactly("order-item:" + itemId, 1).getFirst();
        RecordedSpans.await(() -> !spans(traceId, SpanKind.PRODUCER).isEmpty());
        assertThat(spans(traceId, SpanKind.CONSUMER)).singleElement()
                .satisfies(consume -> assertThat(consume.getParentSpanId()).isEqualTo(parentSpanId));
        SpanData produce = spans(traceId, SpanKind.PRODUCER).getFirst();
        assertThat(header(record, TRACEPARENT)).startsWith("00-" + traceId + "-" + produce.getSpanId() + "-");
    }

    @Test
    void 처리에_실패해_다시_받은_요청도_같은_trace에_소비_span을_남긴다() throws Exception {
        long inquiryId = 704L;
        Long itemId = filedItem(inquiryId);
        String traceId = TraceRequests.randomTraceId();
        String parentSpanId = TraceRequests.randomSpanId();
        doThrow(new OptimisticLockingFailureException("재시도 trace 확인"))
                .doCallRealMethod()
                .when(lectureObjectionFacade).resolve(any());

        send(inquiryId, new LectureObjectionResolutionRequestedEvent("RESOLVE:" + inquiryId, inquiryId, itemId, false,
                ADMIN_ID), TraceRequests.traceparent(traceId, parentSpanId, true));

        awaitExactly("order-item:" + itemId, 1);
        RecordedSpans.await(() -> spans(traceId, SpanKind.CONSUMER).size() >= 2);
        assertThat(spans(traceId, SpanKind.CONSUMER)).hasSize(2)
                .allSatisfy(consume -> assertThat(consume.getParentSpanId()).isEqualTo(parentSpanId))
                .anySatisfy(failed -> assertThat(failed.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR));
    }

    @Test
    void 처리에_계속_실패한_요청은_재시도와_DLT_전송까지_같은_trace에_남는다() throws Exception {
        long inquiryId = 705L;
        Long itemId = filedItem(inquiryId);
        String key = "inquiry:" + inquiryId;
        String traceId = TraceRequests.randomTraceId();
        String parentSpanId = TraceRequests.randomSpanId();
        doThrow(new OptimisticLockingFailureException("DLT trace 확인")).when(lectureObjectionFacade).resolve(any());
        try (KafkaConsumer<String, String> deadLetters = consumerAtEnd(OBJECTION_COMMAND_DLT)) {

            send(inquiryId, new LectureObjectionResolutionRequestedEvent("RESOLVE:" + inquiryId, inquiryId, itemId,
                    false, ADMIN_ID), TraceRequests.traceparent(traceId, parentSpanId, true));

            ConsumerRecord<String, String> record = awaitDeadLetter(deadLetters, key);
            RecordedSpans.await(() -> !spans(traceId, SpanKind.PRODUCER).isEmpty());
            // 처음 1번 + 재시도 3번(LectureKafkaConfig)
            List<SpanData> consumes = spans(traceId, SpanKind.CONSUMER);
            assertThat(consumes).hasSize(4)
                    .allSatisfy(consume -> assertThat(consume.getParentSpanId()).isEqualTo(parentSpanId));
            SpanData deadLetter = spans(traceId, SpanKind.PRODUCER).getFirst();
            assertThat(consumes).extracting(SpanData::getSpanId).contains(deadLetter.getParentSpanId());
            assertThat(header(record, TRACEPARENT)).startsWith("00-" + traceId + "-" + deadLetter.getSpanId() + "-");
        }
    }

    // 역직렬화 실패는 Spring Kafka가 리스너 어댑터를 부르기 전에 끝나 consumer span을 끝내지 않는다(내보내지 않음).
    // DLT 전송 span과 DLT 메시지의 traceparent는 같은 trace로 남는다
    @Test
    void 읽지_못한_요청도_DLT_전송은_같은_trace에_남는다() throws Exception {
        String key = "inquiry:706";
        String traceId = TraceRequests.randomTraceId();
        try (KafkaConsumer<String, String> deadLetters = consumerAtEnd(OBJECTION_COMMAND_DLT)) {

            sendUnreadable(key, TraceRequests.traceparent(traceId, TraceRequests.randomSpanId(), true));

            ConsumerRecord<String, String> record = awaitDeadLetter(deadLetters, key);
            RecordedSpans.await(() -> !spans(traceId, SpanKind.PRODUCER).isEmpty());
            SpanData deadLetter = spans(traceId, SpanKind.PRODUCER).getFirst();
            assertThat(header(record, TRACEPARENT)).startsWith("00-" + traceId + "-" + deadLetter.getSpanId() + "-");
        }
    }

    @Test
    void 강의_토픽과_판단_철회_요청_DLT는_partition_3개로_선언한다() {
        String deadLetter = LectureKafkaConfig.deadLetterTopic(CustomerSupportEventTopic.NAME,
                LectureKafkaConfig.OBJECTION_COMMAND_GROUP);
        assertThat(kafkaAdmin.describeTopics(LectureEventTopic.NAME, deadLetter))
                .allSatisfy((name, description) -> assertThat(description.partitions()).hasSize(3));
    }

    // ---------------------------------------------------------------------------------------------

    /** 이의 제기를 접수한 주문 항목 */
    private Long filedItem(long inquiryId) {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        Long itemId = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                        List.of(lectureRepository.save(lecture)), LocalDateTime.now(LectureConfig.ZONE)))
                .getItems().getFirst().getId();
        lectureObjectionFacade.file(LectureObjectionFileRequest.of(inquiryId, itemId, BUYER_ID, "내용이 다릅니다."));
        return itemId;
    }

    /** 고객센터 앱처럼 레코드를 JSON으로 싣고 클래스 이름을 타입 헤더({@code __TypeId__})로 붙인다. */
    private void send(long inquiryId, Object event) {
        send(inquiryId, event, null);
    }

    /** {@code traceparent}(없으면 null)를 헤더에 실어, trace를 계측한 고객센터 앱이 보낸 것처럼 보낸다 (#630) */
    private void send(long inquiryId, Object event, String traceparent) {
        try (Producer<String, Object> producer = new KafkaProducer<>(Map.copyOf(kafkaAdmin.getConfigurationProperties()),
                new StringSerializer(), new JacksonJsonSerializer<>())) {
            ProducerRecord<String, Object> record = new ProducerRecord<>(CustomerSupportEventTopic.NAME,
                    "inquiry:" + inquiryId, event);
            if (traceparent != null) {
                record.headers().add(TRACEPARENT, traceparent.getBytes(StandardCharsets.UTF_8));
            }
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSON으로 읽을 수 없는 본문을 보낸다. 역직렬화에 실패해 재시도 없이 DLT로 가야 한다 */
    private void sendUnreadable(String key, String traceparent) {
        try (Producer<String, String> producer = new KafkaProducer<>(Map.copyOf(kafkaAdmin.getConfigurationProperties()),
                new StringSerializer(), new StringSerializer())) {
            ProducerRecord<String, String> record = new ProducerRecord<>(CustomerSupportEventTopic.NAME, key, "{not json");
            record.headers().add(TRACEPARENT, traceparent.getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** DLT에 이 key의 메시지가 올 때까지 읽는다 */
    private ConsumerRecord<String, String> awaitDeadLetter(KafkaConsumer<String, String> deadLetters, String key) {
        List<ConsumerRecord<String, String>> arrived = new CopyOnWriteArrayList<>();
        await().atMost(Duration.ofSeconds(30)).pollInSameThread().untilAsserted(() -> {
            deadLetters.poll(Duration.ofMillis(200)).forEach(arrived::add);
            assertThat(arrived).anyMatch(record -> key.equals(record.key()));
        });
        return arrived.stream().filter(record -> key.equals(record.key())).findFirst().orElseThrow();
    }

    private List<SpanData> spans(String traceId, SpanKind kind) {
        return spans.all().stream()
                .filter(span -> span.getTraceId().equals(traceId) && span.getKind() == kind)
                .toList();
    }

    /** 이 key의 메시지가 count건 온 뒤, 잠시 더 읽어도 그대로 count건인지 (중복 발행이 없는지) */
    private List<ConsumerRecord<String, String>> awaitExactly(String key, int count) {
        await().atMost(TIMEOUT).pollInSameThread()
                .untilAsserted(() -> assertThat(records(key)).hasSizeGreaterThanOrEqualTo(count));
        await().during(HOLD).atMost(TIMEOUT).pollInSameThread()
                .untilAsserted(() -> assertThat(records(key)).hasSize(count));
        return records(key);
    }

    /** 지금까지 받은 메시지 중 이 key의 것. 부를 때마다 새로 온 메시지를 더 읽는다 */
    private List<ConsumerRecord<String, String>> records(String key) {
        consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        return received.stream().filter(record -> key.equals(record.key())).toList();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
