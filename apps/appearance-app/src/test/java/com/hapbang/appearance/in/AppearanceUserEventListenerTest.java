package com.hapbang.appearance.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

import com.hapbang.appearance.config.AppearanceKafkaConfig;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserLoggedOutEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 회원 앱이 {@code member.events.v1}에 보낸 회원 이벤트가 출연 회원 복제본에 반영되는지 실제 Kafka로 확인한다 (#394).
 * <ul>
 *     <li>회원 앱처럼 shared 레코드를 JSON(타입 헤더 {@code __TypeId__} 포함)으로, key는 회원 ID로 보낸다.</li>
 *     <li>컨텍스트와 소비 그룹을 테스트끼리 같이 쓰므로 테스트마다 다른 회원 ID를 써서 메시지와 DLT 기록이 섞이지 않게 한다.</li>
 *     <li>리스너·토픽 생성을 켠 설정이라 컨텍스트를 따로 띄우고, 끝나면 닫아({@link DirtiesContext}) DB 연결과 리스너를 정리한다.</li>
 * </ul>
 * 강의 앱의 같은 테스트(#429 {@code LectureMemberEventListenerTest})와 같은 경우를 확인한다.
 */
@AppearanceIntegrationTest
@Import({KafkaTestcontainersConfig.class, AppearanceUserEventListenerTest.MemberTopicConfig.class})
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true"})
@DirtiesContext
class AppearanceUserEventListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** 재시도 3번(1초, 2초, 4초)을 마치고 DLT로 갈 때까지 기다리는 시간 */
    private static final Duration DEAD_LETTER_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEAD_LETTER_TOPIC = AppearanceKafkaConfig.deadLetterTopic(
            AppearanceKafkaConfig.MEMBER_EVENTS_TOPIC, AppearanceKafkaConfig.MEMBER_REPLICA_GROUP);

    /** 회원 앱이 선언하는 업무 토픽을 대신 만든다. 출연 앱은 DLT만 선언한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class MemberTopicConfig {

        @Bean
        NewTopic memberEventsTopic() {
            return TopicBuilder.name(AppearanceKafkaConfig.MEMBER_EVENTS_TOPIC).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Autowired
    AppearanceUserRepository appearanceUserRepository;

    @Test
    @DisplayName("회원 가입 이벤트를 받으면 출연 회원을 만든다 (닉네임·역할·상태·회원 버전)")
    void joined() {
        long userId = 101L;

        send(userId, new UserJoinedEvent(user(userId, "게스트", Role.INFLUENCER, 0L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(appearanceUserRepository.findById(userId))
                .hasValueSatisfying(replica -> {
                    assertThat(replica.getNickname()).isEqualTo("게스트");
                    assertThat(replica.getRole()).isEqualTo(Role.INFLUENCER);
                    assertThat(replica.getStatus()).isEqualTo(UserStatus.ACTIVE);
                    assertThat(replica.getMemberVersion()).isZero();
                }));
    }

    @Test
    @DisplayName("회원 정보 변경 이벤트를 받으면 닉네임·역할·상태를 갱신한다")
    void modified() {
        long userId = 102L;
        send(userId, new UserJoinedEvent(user(userId, "호스트", Role.INFLUENCER, 0L)));

        send(userId, new UserModifiedEvent(
                new UserDto(userId, "인증호스트", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED, 1L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(appearanceUserRepository.findById(userId))
                .hasValueSatisfying(replica -> {
                    assertThat(replica.getNickname()).isEqualTo("인증호스트");
                    assertThat(replica.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
                    assertThat(replica.getStatus()).isEqualTo(UserStatus.SUSPENDED);
                    assertThat(replica.getMemberVersion()).isEqualTo(1L);
                }));
    }

    @Test
    @DisplayName("같은 가입 이벤트가 다시 오거나 옛 버전 변경이 늦게 오면 무시한다 (중복·순서 뒤바뀜)")
    void duplicateAndStale() {
        long userId = 103L;
        UserJoinedEvent joined = new UserJoinedEvent(user(userId, "가입닉네임", Role.INFLUENCER, 0L));
        send(userId, joined);
        send(userId, new UserModifiedEvent(user(userId, "최신닉네임", Role.VERIFIED_INFLUENCER, 2L)));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(appearanceUserRepository.findById(userId))
                .hasValueSatisfying(replica -> assertThat(replica.getMemberVersion()).isEqualTo(2L)));

        send(userId, joined);
        send(userId, new UserModifiedEvent(user(userId, "옛닉네임", Role.INFLUENCER, 1L)));
        send(userId, new UserModifiedEvent(user(userId, "같은버전", Role.INFLUENCER, 2L)));

        await().during(Duration.ofSeconds(1)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(appearanceUserRepository.findById(userId))
                        .hasValueSatisfying(replica -> {
                            assertThat(replica.getNickname()).isEqualTo("최신닉네임");
                            assertThat(replica.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
                            assertThat(replica.getMemberVersion()).isEqualTo(2L);
                        }));
        assertThat(deadLettersOf(userId, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("출연이 쓰지 않는 회원 이벤트(로그아웃)는 무시하고 다음 이벤트를 처리한다")
    void ignoresUnknownType() {
        long userId = 104L;

        send(userId, new UserLoggedOutEvent(userId, "session-1"));
        send(userId, new UserJoinedEvent(user(userId, "다음이벤트", Role.INFLUENCER, 0L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(appearanceUserRepository.findById(userId)).isPresent());
        assertThat(deadLettersOf(userId, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("가입보다 먼저 온 변경 이벤트는 출연 회원을 만들지 않고, 재시도(1·2·4초) 뒤 출연 DLT로 보낸다")
    void modifiedBeforeJoinedGoesToDeadLetter() {
        long userId = 105L;

        send(userId, new UserModifiedEvent(user(userId, "가입전변경", Role.INFLUENCER, 1L)));

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLettersOf(userId).getFirst();
        assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).contains("가입전변경");
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(AppearanceKafkaConfig.MEMBER_EVENTS_TOPIC);
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).isEqualTo(AppearanceException.class.getName());
        assertThat(delayUntilDeadLetter(deadLetter)).isGreaterThanOrEqualTo(Duration.ofSeconds(7));
        assertThat(appearanceUserRepository.findById(userId)).isEmpty();
    }

    @Test
    @DisplayName("깨진 메시지는 재시도 없이 원본 그대로 DLT로 보내고 다음 메시지를 처리한다")
    void brokenMessageGoesToDeadLetter() {
        long userId = 106L;
        byte[] broken = "{not json".getBytes(StandardCharsets.UTF_8);

        sendRaw(userId, broken, UserJoinedEvent.class.getName());
        send(userId, new UserJoinedEvent(user(userId, "깨진메시지다음", Role.INFLUENCER, 0L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(appearanceUserRepository.findById(userId)).isPresent());
        List<ConsumerRecord<String, byte[]>> deadLetters = awaitDeadLettersOf(userId);
        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.getFirst().value()).isEqualTo(broken);
        assertThat(delayUntilDeadLetter(deadLetters.getFirst())).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("출연 DLT는 partition 3개, 보존 7일로 만든다")
    void deadLetterTopicDeclared() throws Exception {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            TopicDescription description = admin.describeTopics(List.of(DEAD_LETTER_TOPIC))
                    .allTopicNames().get(10, TimeUnit.SECONDS).get(DEAD_LETTER_TOPIC);
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, DEAD_LETTER_TOPIC);
            Config config = admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS).get(resource);

            assertThat(DEAD_LETTER_TOPIC).isEqualTo("member.events.v1.appearance-app.member-replica.DLT");
            assertThat(description.partitions()).hasSize(3);
            assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value())
                    .isEqualTo(String.valueOf(Duration.ofDays(7).toMillis()));
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static UserDto user(long id, String nickname, Role role, long version) {
        return new UserDto(id, nickname, role, UserStatus.ACTIVE, version);
    }

    /** 회원 앱처럼 레코드를 JSON으로 싣고 클래스 이름을 타입 헤더({@code __TypeId__})로 붙인다 */
    private void send(long userId, Object event) {
        try (Producer<String, Object> producer = new KafkaProducer<>(producerProperties(),
                new StringSerializer(), new JacksonJsonSerializer<>())) {
            producer.send(new ProducerRecord<>(AppearanceKafkaConfig.MEMBER_EVENTS_TOPIC, String.valueOf(userId), event))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendRaw(long userId, byte[] value, String typeId) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                AppearanceKafkaConfig.MEMBER_EVENTS_TOPIC, String.valueOf(userId), value);
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

    /** DLT를 처음부터 읽어 이 회원 key의 메시지만 모은다 */
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
