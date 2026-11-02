package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

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
@Import({KafkaTestcontainersConfig.class, LectureObjectionKafkaTest.CustomerSupportTopicConfig.class})
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true",
        "spring.modulith.events.externalization.enabled=true"})
@WithMockUser(roles = "INTERNAL")
@DirtiesContext
class LectureObjectionKafkaTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration HOLD = Duration.ofMillis(800);

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

    @Autowired
    private LectureObjectionFacade lectureObjectionFacade;

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
        Map<String, Object> properties = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "lecture-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(LectureEventTopic.NAME));
        // 파티션을 받을 때까지 읽은 뒤 끝으로 옮긴다. 이후 이 테스트가 만든 메시지만 받는다
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
        try (Producer<String, Object> producer = new KafkaProducer<>(Map.copyOf(kafkaAdmin.getConfigurationProperties()),
                new StringSerializer(), new JacksonJsonSerializer<>())) {
            producer.send(new ProducerRecord<>(CustomerSupportEventTopic.NAME, "inquiry:" + inquiryId, event))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
