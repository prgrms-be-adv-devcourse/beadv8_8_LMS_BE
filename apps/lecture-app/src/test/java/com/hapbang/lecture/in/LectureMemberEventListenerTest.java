package com.hapbang.lecture.in;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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

import com.hapbang.lecture.config.LectureKafkaConfig;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserLoggedOutEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 회원 앱이 {@code member.events.v1}에 보낸 회원 이벤트가 강의 회원 복제본에 반영되는지 실제 Kafka로 검증한다 (#429).
 * <p>
 * 회원 앱처럼 shared 레코드를 JSON(타입 헤더 포함)으로, key는 회원 ID로 보낸다. 컨텍스트와 소비 그룹을 테스트끼리 공유하므로
 * 테스트마다 다른 회원 ID를 써서 서로의 메시지와 DLT 기록이 섞이지 않게 한다.
 * <p>
 * 설정이 달라 다른 테스트와 컨텍스트를 공유하지 못한다. 끝나면 컨텍스트를 닫아 DB 연결과 리스너를 정리한다.
 * 캐시에 남기면 테스트 PostgreSQL 연결 수가 한도를 넘어 뒤에 뜨는 컨텍스트가 실패한다.
 */
@IntegrationTest
@Import({KafkaTestcontainersConfig.class, LectureMemberEventListenerTest.MemberTopicConfig.class})
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true"})
@DirtiesContext
class LectureMemberEventListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** 재시도 3번(1초, 2초, 4초)을 마치고 DLT로 갈 때까지 기다리는 시간 */
    private static final Duration DEAD_LETTER_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEAD_LETTER_TOPIC = LectureKafkaConfig.deadLetterTopic(
            LectureKafkaConfig.MEMBER_EVENTS_TOPIC, LectureKafkaConfig.MEMBER_REPLICA_GROUP);

    /** 회원 앱이 선언하는 업무 토픽을 대신 만든다. 강의 앱은 DLT만 선언한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class MemberTopicConfig {

        @Bean
        NewTopic memberEventsTopic() {
            return TopicBuilder.name(LectureKafkaConfig.MEMBER_EVENTS_TOPIC).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @Test
    void 회원_가입_이벤트를_받으면_강의_회원으로_복제한다() {
        long userId = 101L;

        send(userId, new UserJoinedEvent(user(userId, "판매자", Role.VERIFIED_INFLUENCER, 0L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(userId))
                .hasValueSatisfying(replica -> {
                    assertThat(replica.getNickname()).isEqualTo("판매자");
                    assertThat(replica.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
                    assertThat(replica.getStatus()).isEqualTo(UserStatus.ACTIVE);
                    assertThat(replica.getMemberVersion()).isZero();
                }));
    }

    @Test
    void 회원_정보_변경_이벤트를_받으면_강의_회원_정보를_갱신한다() {
        long userId = 102L;
        send(userId, new UserJoinedEvent(user(userId, "구매자", Role.USER, 0L)));

        send(userId, new UserModifiedEvent(user(userId, "판매자", Role.VERIFIED_INFLUENCER, 1L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(userId))
                .hasValueSatisfying(replica -> {
                    assertThat(replica.getNickname()).isEqualTo("판매자");
                    assertThat(replica.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
                    assertThat(replica.getMemberVersion()).isEqualTo(1L);
                }));
    }

    @Test
    void 같은_가입_이벤트가_다시_오거나_옛_버전_변경이_늦게_오면_무시한다() {
        long userId = 103L;
        UserJoinedEvent joined = new UserJoinedEvent(user(userId, "가입닉네임", Role.USER, 0L));
        send(userId, joined);
        send(userId, new UserModifiedEvent(user(userId, "최신닉네임", Role.VERIFIED_INFLUENCER, 2L)));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(userId))
                .hasValueSatisfying(replica -> assertThat(replica.getMemberVersion()).isEqualTo(2L)));

        send(userId, joined);
        send(userId, new UserModifiedEvent(user(userId, "옛닉네임", Role.INFLUENCER, 1L)));

        await().during(Duration.ofSeconds(1)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(lectureUserRepository.findById(userId))
                        .hasValueSatisfying(replica -> {
                            assertThat(replica.getNickname()).isEqualTo("최신닉네임");
                            assertThat(replica.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
                            assertThat(replica.getMemberVersion()).isEqualTo(2L);
                        }));
        assertThat(deadLettersOf(userId, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void 강의가_쓰지_않는_회원_이벤트는_무시하고_다음_이벤트를_처리한다() {
        long userId = 104L;

        send(userId, new UserLoggedOutEvent(userId, "session-1"));
        send(userId, new UserJoinedEvent(user(userId, "다음이벤트", Role.USER, 0L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(userId)).isPresent());
        assertThat(deadLettersOf(userId, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void 가입보다_먼저_온_변경_이벤트는_재시도_뒤_강의_DLT로_보낸다() {
        long userId = 105L;

        send(userId, new UserModifiedEvent(user(userId, "가입전변경", Role.USER, 1L)));

        List<ConsumerRecord<String, byte[]>> deadLetters = awaitDeadLettersOf(userId);
        ConsumerRecord<String, byte[]> deadLetter = deadLetters.getFirst();
        assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).contains("가입전변경");
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(LectureKafkaConfig.MEMBER_EVENTS_TOPIC);
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).isEqualTo(LectureException.class.getName());
        // 1초·2초·4초 재시도를 거쳤다
        assertThat(delayUntilDeadLetter(deadLetter)).isGreaterThanOrEqualTo(Duration.ofSeconds(7));
        assertThat(lectureUserRepository.findById(userId)).isEmpty();
    }

    @Test
    void 깨진_메시지는_재시도_없이_원본_그대로_DLT로_보내고_다음_메시지를_처리한다() {
        long userId = 106L;
        byte[] broken = "{not json".getBytes(StandardCharsets.UTF_8);

        sendRaw(userId, broken, UserJoinedEvent.class.getName());
        send(userId, new UserJoinedEvent(user(userId, "깨진메시지다음", Role.USER, 0L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(userId)).isPresent());
        List<ConsumerRecord<String, byte[]>> deadLetters = awaitDeadLettersOf(userId);
        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.getFirst().value()).isEqualTo(broken);
        // 재시도(최소 1초)를 거치지 않았다
        assertThat(delayUntilDeadLetter(deadLetters.getFirst())).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void 강의_DLT는_partition_3개와_보존_7일로_만든다() throws Exception {
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

    private static UserDto user(long id, String nickname, Role role, long version) {
        return new UserDto(id, nickname, role, UserStatus.ACTIVE, version);
    }

    /** 회원 앱처럼 레코드를 JSON으로 싣고 클래스 이름을 타입 헤더({@code __TypeId__})로 붙인다. */
    private void send(long userId, Object event) {
        try (Producer<String, Object> producer = new KafkaProducer<>(producerProperties(),
                new StringSerializer(), new JacksonJsonSerializer<>())) {
            producer.send(new ProducerRecord<>(LectureKafkaConfig.MEMBER_EVENTS_TOPIC, String.valueOf(userId), event))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendRaw(long userId, byte[] value, String typeId) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                LectureKafkaConfig.MEMBER_EVENTS_TOPIC, String.valueOf(userId), value);
        record.headers().add("__TypeId__", typeId.getBytes(StandardCharsets.UTF_8));
        try (Producer<String, byte[]> producer = new KafkaProducer<>(producerProperties(),
                new StringSerializer(), new ByteArraySerializer())) {
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> producerProperties() {
        return Map.copyOf(kafkaAdmin.getConfigurationProperties());
    }

    private List<ConsumerRecord<String, byte[]>> awaitDeadLettersOf(long userId) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        await().atMost(DEAD_LETTER_TIMEOUT).untilAsserted(() -> {
            found.clear();
            found.addAll(deadLettersOf(userId, Duration.ofSeconds(2)));
            assertThat(found).isNotEmpty();
        });
        return found;
    }

    /** DLT를 처음부터 읽어 이 회원 key의 메시지만 모은다. */
    private List<ConsumerRecord<String, byte[]>> deadLettersOf(long userId, Duration pollFor) {
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
                    if (String.valueOf(userId).equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }

    /** 원본 메시지가 기록된 시각부터 DLT에 기록된 시각까지 */
    private static Duration delayUntilDeadLetter(ConsumerRecord<?, ?> deadLetter) {
        long originalTimestamp = ByteBuffer.wrap(
                deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP).value()).getLong();
        return Duration.ofMillis(deadLetter.timestamp() - originalTimestamp);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
