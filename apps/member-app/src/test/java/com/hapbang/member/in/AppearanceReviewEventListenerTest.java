package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.app.MemberAppearanceRatingUseCase;
import com.hapbang.member.domain.AppearanceRatingSummary;
import com.hapbang.member.domain.RatingSummary;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.global.config.MemberKafkaConfig;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;
import com.hapbang.shared.appearance.event.AppearanceEventTopic;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

/**
 * 출연 앱이 {@code appearance.events.v1}에 보낸 리뷰 공개 이벤트가 회원 출연 평점에 반영되는지 실제 Kafka로 검증한다 (#555).
 * <p>
 * 출연 앱처럼 shared 레코드를 JSON(타입 헤더 포함)으로, key는 받은 회원으로 보낸다. 컨텍스트와 소비 그룹을 테스트끼리 공유하므로
 * 테스트마다 다른 회원을 써서 서로의 메시지와 DLT 기록이 섞이지 않게 한다.
 * <p>
 * 리스너를 켜는 설정이 달라 다른 테스트와 컨텍스트를 공유하지 못한다. 끝나면 컨텍스트를 닫아 DB 연결과 리스너를 정리한다.
 */
@MemberIntegrationTest
@Import(AppearanceReviewEventListenerTest.AppearanceTopicConfig.class)
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=true")
@DirtiesContext
class AppearanceReviewEventListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** 재시도 3번(1초, 2초, 4초)을 마치고 DLT로 갈 때까지 기다리는 시간 */
    private static final Duration DEAD_LETTER_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEAD_LETTER_TOPIC = MemberKafkaConfig.deadLetterTopic(
            AppearanceEventTopic.NAME, MemberKafkaConfig.APPEARANCE_REVIEW_GROUP);
    private static final AtomicLong REVIEW_ID = new AtomicLong(1);

    /** 출연 앱이 선언하는 업무 토픽을 대신 만든다. 회원 앱은 DLT만 선언한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class AppearanceTopicConfig {

        @Bean
        NewTopic appearanceEventsTopic() {
            return TopicBuilder.name(AppearanceEventTopic.NAME).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Autowired
    UserRepository userRepository;

    @Autowired
    MemberAppearanceRatingUseCase memberAppearanceRatingUseCase;

    @Test
    @DisplayName("리뷰 공개 이벤트를 받으면 받은 회원의 출연 평점에 역할별로 더한다")
    void apply() {
        User user = userRepository.save(MemberFixture.user());

        send(user.getId(), review(user.getId(), AppearanceParticipantRole.HOST, 5));
        send(user.getId(), review(user.getId(), AppearanceParticipantRole.GUEST, 3));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(memberAppearanceRatingUseCase.find(user.getId()))
                .isEqualTo(new AppearanceRatingSummary(
                        new RatingSummary(4.0, 2),
                        new RatingSummary(5.0, 1),
                        new RatingSummary(3.0, 1))));
    }

    @Test
    @DisplayName("같은 리뷰가 다시 오면 한 번만 반영하고 DLT로 보내지 않는다")
    void duplicate() {
        User user = userRepository.save(MemberFixture.user());
        AppearanceReviewPublishedEvent event = review(user.getId(), AppearanceParticipantRole.GUEST, 4);

        send(user.getId(), event);
        send(user.getId(), event);
        send(user.getId(), review(user.getId(), AppearanceParticipantRole.GUEST, 2));

        // 세 번째(다른 리뷰)까지 처리됐으면 앞의 중복도 처리가 끝났다
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(memberAppearanceRatingUseCase.find(user.getId()).asGuest())
                .isEqualTo(new RatingSummary(3.0, 2)));
        assertThat(deadLettersOf(user.getId(), Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("회원이 쓰지 않는 출연 이벤트는 무시하고 다음 이벤트를 처리한다")
    void ignoreOtherTypes() {
        User user = userRepository.save(MemberFixture.user());

        send(user.getId(), new AppearanceCancelRequestedEvent(null));
        send(user.getId(), review(user.getId(), AppearanceParticipantRole.HOST, 4));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(memberAppearanceRatingUseCase.find(user.getId()).asHost())
                .isEqualTo(new RatingSummary(4.0, 1)));
        assertThat(deadLettersOf(user.getId(), Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("필수 값이 없거나 별점이 범위 밖이면 재시도 없이 DLT로 보내고 다음 이벤트를 처리한다")
    void rejected() {
        User user = userRepository.save(MemberFixture.user());
        long userId = user.getId();

        send(userId, new AppearanceReviewPublishedEvent(REVIEW_ID.getAndIncrement(), 1L, 999L, userId, null, 5,
                LocalDateTime.now()));
        send(userId, review(userId, AppearanceParticipantRole.HOST, 0));
        send(userId, review(userId, AppearanceParticipantRole.HOST, 5));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(memberAppearanceRatingUseCase.find(userId).total())
                .isEqualTo(new RatingSummary(5.0, 1)));
        List<ConsumerRecord<String, byte[]>> deadLetters = awaitDeadLettersOf(userId, 2);
        assertThat(deadLetters).allSatisfy(deadLetter -> {
            assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(AppearanceEventTopic.NAME);
            assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("ListenerExecutionFailedException");
            assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                    .isEqualTo(RejectedEventException.class.getName());
            // 재시도(최소 1초)를 거치지 않았다
            assertThat(delayUntilDeadLetter(deadLetter)).isLessThan(Duration.ofSeconds(1));
        });
    }

    @Test
    @DisplayName("받은 회원이 없으면 재시도 뒤 DLT로 보낸다")
    void unknownReviewee() {
        long unknownUserId = 9_000_000L + REVIEW_ID.get();

        send(unknownUserId, review(unknownUserId, AppearanceParticipantRole.GUEST, 5));

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLettersOf(unknownUserId, 1).getFirst();
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(AppearanceEventTopic.NAME);
        // 1초·2초·4초 재시도를 거쳤다
        assertThat(delayUntilDeadLetter(deadLetter)).isGreaterThanOrEqualTo(Duration.ofSeconds(7));
        assertThat(memberAppearanceRatingUseCase.find(unknownUserId)).isEqualTo(AppearanceRatingSummary.EMPTY);
    }

    @Test
    @DisplayName("회원 앱의 출연 DLT는 partition 3개와 보존 7일로 만든다")
    void deadLetterTopic() throws Exception {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            TopicDescription description = admin.describeTopics(List.of(DEAD_LETTER_TOPIC))
                    .allTopicNames().get(10, TimeUnit.SECONDS).get(DEAD_LETTER_TOPIC);
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, DEAD_LETTER_TOPIC);
            Config config = admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS).get(resource);

            assertThat(description.partitions()).hasSize(3);
            assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value())
                    .isEqualTo(String.valueOf(Duration.ofDays(7).toMillis()));
        }
    }

    private static AppearanceReviewPublishedEvent review(long revieweeId, AppearanceParticipantRole role, int rating) {
        long reviewId = REVIEW_ID.getAndIncrement();
        return new AppearanceReviewPublishedEvent(reviewId, 100L + reviewId, 999L, revieweeId, role, rating,
                LocalDateTime.now());
    }

    /** 받은 회원 key. 출연 앱의 리뷰 공개 이벤트와 같은 모양 */
    private static String key(long revieweeId) {
        return "appearance-review-reviewee:" + revieweeId;
    }

    /** 출연 앱처럼 레코드를 JSON으로 싣고 클래스 이름을 타입 헤더({@code __TypeId__})로 붙인다. */
    private void send(long revieweeId, Object event) {
        try (Producer<String, Object> producer = new KafkaProducer<>(Map.copyOf(kafkaAdmin.getConfigurationProperties()),
                new StringSerializer(), new JacksonJsonSerializer<>())) {
            producer.send(new ProducerRecord<>(AppearanceEventTopic.NAME, key(revieweeId), event))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ConsumerRecord<String, byte[]>> awaitDeadLettersOf(long revieweeId, int count) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        await().atMost(DEAD_LETTER_TIMEOUT).untilAsserted(() -> {
            found.clear();
            found.addAll(deadLettersOf(revieweeId, Duration.ofSeconds(2)));
            assertThat(found).hasSize(count);
        });
        return found;
    }

    /** DLT를 처음부터 읽어 이 회원 key의 메시지만 모은다. */
    private List<ConsumerRecord<String, byte[]>> deadLettersOf(long revieweeId, Duration pollFor) {
        Map<String, Object> properties = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (Consumer<String, byte[]> consumer = new DefaultKafkaConsumerFactory<>(properties,
                new StringDeserializer(), new ByteArrayDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(DEAD_LETTER_TOPIC));
            long deadline = System.nanoTime() + pollFor.toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).records(DEAD_LETTER_TOPIC).forEach(record -> {
                    if (key(revieweeId).equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    /** 원본 메시지 시각부터 DLT에 들어간 시각까지 */
    private static Duration delayUntilDeadLetter(ConsumerRecord<String, byte[]> deadLetter) {
        long originalTimestamp = ByteBuffer.wrap(
                deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP).value()).getLong();
        return Duration.between(Instant.ofEpochMilli(originalTimestamp), Instant.ofEpochMilli(deadLetter.timestamp()));
    }
}
