package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.billing.app.BillingUserSyncUseCase;
import com.hapbang.billing.global.config.BillingKafkaConfig;
import com.hapbang.billing.out.BillingUserRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserLoggedOutEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 회원 앱이 {@code member.events.v1}에 보낸 회원 이벤트가 두 소비 그룹(복제본·지갑 생성)으로 반영되는지 실제 Kafka로 검증한다 (#430).
 * <p>
 * 회원 앱처럼 shared 레코드를 JSON(타입 헤더 포함)으로, key는 회원 ID로 보낸다. 컨텍스트와 소비 그룹을 테스트끼리 공유하므로
 * 테스트마다 다른 회원 ID를 써서 서로의 메시지와 DLT 기록이 섞이지 않게 한다. 테스트가 끝나면 테이블을 비우지만({@code @IntegrationTest}),
 * 각 테스트는 자기 회원의 처리를 기다린 뒤 끝나므로 다음 테스트에 남는 처리가 없다.
 * <p>
 * 그룹 간 실패 격리를 보려고 복제본 반영({@link BillingUserSyncUseCase})을 스파이로 바꾸고, 정한 회원 ID에서만 실패시킨다.
 * 설정이 달라 다른 테스트와 컨텍스트를 공유하지 못한다. 끝나면 컨텍스트를 닫아 DB 연결과 리스너를 정리한다.
 */
@IntegrationTest
@Import({KafkaTestcontainersConfig.class, BillingMemberEventKafkaTest.MemberTopicConfig.class})
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true",
        "spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.minimum-idle=1"})
@DirtiesContext
class BillingMemberEventKafkaTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** 재시도 3번(1초, 2초, 4초)을 마치고 DLT로 갈 때까지 기다리는 시간 */
    private static final Duration DEAD_LETTER_TIMEOUT = Duration.ofSeconds(30);
    private static final String REPLICA_DLT = BillingKafkaConfig.deadLetterTopic(
            BillingKafkaConfig.MEMBER_EVENTS_TOPIC, BillingKafkaConfig.MEMBER_REPLICA_GROUP);
    private static final String WALLET_DLT = BillingKafkaConfig.deadLetterTopic(
            BillingKafkaConfig.MEMBER_EVENTS_TOPIC, BillingKafkaConfig.WALLET_CREATION_GROUP);
    /** 복제본 반영을 일부러 실패시키는 회원 */
    private static final long REPLICA_FAILING_USER = 309L;

    /** 회원 앱이 선언하는 업무 토픽을 대신 만든다. 지갑 앱은 DLT만 선언한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class MemberTopicConfig {

        @Bean
        NewTopic memberEventsTopic() {
            return TopicBuilder.name(BillingKafkaConfig.MEMBER_EVENTS_TOPIC).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private BillingUserRepository billingUserRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private BillingUserSyncUseCase billingUserSyncUseCase;

    @Test
    @DisplayName("가입 이벤트를 받으면 지갑(잔액 0)과 회원 복제본을 각각의 그룹이 만든다")
    void joined_createsWalletAndReplica() {
        long userId = 301L;

        send(userId, new UserJoinedEvent(user(userId, "가입", Role.INFLUENCER, 0L)));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(walletRepository.findByUserId(userId)).hasValueSatisfying(wallet -> {
                assertThat(wallet.getChargeBalanceHp()).isZero();
                assertThat(wallet.getRevenueBalanceHp()).isZero();
            });
            assertThat(billingUserRepository.findById(userId)).hasValueSatisfying(replica -> {
                assertThat(replica.getRole()).isEqualTo(Role.INFLUENCER);
                assertThat(replica.getMemberVersion()).isZero();
            });
        });
    }

    @Test
    @DisplayName("변경 이벤트는 복제본만 갱신하고 지갑은 그대로 하나다")
    void modified_updatesReplicaOnly() {
        long userId = 302L;
        send(userId, new UserJoinedEvent(user(userId, "가입", Role.USER, 0L)));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(walletRepository.findByUserId(userId)).isPresent());
        Long walletId = walletRepository.findByUserId(userId).orElseThrow().getId();

        send(userId, new UserModifiedEvent(new UserDto(userId, "정지", Role.USER, UserStatus.SUSPENDED, 1L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(billingUserRepository.findById(userId))
                .hasValueSatisfying(replica -> {
                    assertThat(replica.getStatus()).isEqualTo(UserStatus.SUSPENDED);
                    assertThat(replica.getMemberVersion()).isEqualTo(1L);
                }));
        assertThat(walletCount(userId)).isEqualTo(1);
        assertThat(walletRepository.findByUserId(userId).orElseThrow().getId()).isEqualTo(walletId);
    }

    @Test
    @DisplayName("같은 가입 이벤트가 두 번 와도 지갑은 하나, 복제본은 한 건이고, 늦게 온 옛 버전 변경은 무시한다")
    void duplicateAndStaleEventsAreIgnored() {
        long userId = 303L;
        UserJoinedEvent joined = new UserJoinedEvent(user(userId, "가입닉네임", Role.USER, 0L));
        send(userId, joined);
        send(userId, new UserModifiedEvent(user(userId, "최신닉네임", Role.VERIFIED_INFLUENCER, 2L)));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(billingUserRepository.findById(userId))
                .hasValueSatisfying(replica -> assertThat(replica.getMemberVersion()).isEqualTo(2L)));

        send(userId, joined);
        send(userId, new UserModifiedEvent(user(userId, "옛닉네임", Role.INFLUENCER, 1L)));

        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(billingUserRepository.findById(userId)).hasValueSatisfying(replica -> {
                assertThat(replica.getNickname()).isEqualTo("최신닉네임");
                assertThat(replica.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
                assertThat(replica.getMemberVersion()).isEqualTo(2L);
            });
            assertThat(walletCount(userId)).isEqualTo(1);
            assertThat(replicaCount(userId)).isEqualTo(1);
        });
        assertThat(deadLettersOf(REPLICA_DLT, userId, Duration.ofSeconds(1))).isEmpty();
        assertThat(deadLettersOf(WALLET_DLT, userId, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("관리자 가입 이벤트는 지갑을 만들지 않지만 복제본은 만든다")
    void adminJoined_replicaWithoutWallet() {
        long userId = 304L;

        send(userId, new UserJoinedEvent(user(userId, "관리자", Role.ADMIN, 0L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(billingUserRepository.findById(userId)).isPresent());
        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(walletRepository.findByUserId(userId)).isEmpty());
        assertThat(deadLettersOf(WALLET_DLT, userId, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("가입보다 변경이 먼저 와도 복제본을 만들고 DLT로 보내지 않는다(BD-103). 지갑은 가입 이벤트로만 만든다")
    void modifiedBeforeJoined_createsReplica() {
        long userId = 305L;

        send(userId, new UserModifiedEvent(user(userId, "변경먼저", Role.INFLUENCER, 1L)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(billingUserRepository.findById(userId))
                .hasValueSatisfying(replica -> assertThat(replica.getMemberVersion()).isEqualTo(1L)));
        assertThat(walletRepository.findByUserId(userId)).isEmpty();
        assertThat(deadLettersOf(REPLICA_DLT, userId, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("쓰지 않는 회원 이벤트(로그아웃)는 두 그룹 모두 무시하고 다음 이벤트를 처리한다")
    void unknownTypeIsIgnored() {
        long userId = 306L;

        send(userId, new UserLoggedOutEvent(userId, "session-1"));
        send(userId, new UserJoinedEvent(user(userId, "다음이벤트", Role.USER, 0L)));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(walletRepository.findByUserId(userId)).isPresent();
            assertThat(billingUserRepository.findById(userId)).isPresent();
        });
        assertThat(deadLettersOf(REPLICA_DLT, userId, Duration.ofSeconds(1))).isEmpty();
        assertThat(deadLettersOf(WALLET_DLT, userId, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("깨진 메시지는 재시도 없이 원본 그대로 두 그룹의 DLT로 각각 보내고, 다음 메시지를 처리한다")
    void brokenMessageGoesToEachGroupDeadLetter() {
        long userId = 307L;
        byte[] broken = "{not json".getBytes(StandardCharsets.UTF_8);

        sendRaw(userId, broken, UserJoinedEvent.class.getName());
        send(userId, new UserJoinedEvent(user(userId, "깨진메시지다음", Role.USER, 0L)));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(walletRepository.findByUserId(userId)).isPresent();
            assertThat(billingUserRepository.findById(userId)).isPresent();
        });
        for (String deadLetterTopic : List.of(REPLICA_DLT, WALLET_DLT)) {
            List<ConsumerRecord<String, byte[]>> deadLetters = awaitDeadLettersOf(deadLetterTopic, userId);
            assertThat(deadLetters).hasSize(1);
            assertThat(deadLetters.getFirst().value()).isEqualTo(broken);
            // 재시도(최소 1초)를 거치지 않았다
            assertThat(delayUntilDeadLetter(deadLetters.getFirst())).isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    @DisplayName("복제본 반영이 계속 실패하면 재시도 뒤 복제본 DLT로만 가고, 지갑 생성 그룹은 그동안 지갑을 만든다")
    void replicaFailureDoesNotBlockWalletCreation() {
        long userId = REPLICA_FAILING_USER;
        doThrow(new IllegalStateException("복제본 반영 실패(테스트)")).when(billingUserSyncUseCase)
                .apply(argThat(user -> user != null && user.id() == REPLICA_FAILING_USER));

        send(userId, new UserJoinedEvent(user(userId, "복제실패", Role.USER, 0L)));

        // 복제본 그룹이 재시도(1·2·4초)하는 동안 지갑 생성 그룹은 먼저 끝낸다
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(walletRepository.findByUserId(userId)).isPresent());
        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLettersOf(REPLICA_DLT, userId).getFirst();
        assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).contains("복제실패");
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(BillingKafkaConfig.MEMBER_EVENTS_TOPIC);
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(delayUntilDeadLetter(deadLetter)).isGreaterThanOrEqualTo(Duration.ofSeconds(7));
        assertThat(billingUserRepository.findById(userId)).isEmpty();
        assertThat(deadLettersOf(WALLET_DLT, userId, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("두 그룹의 DLT는 partition 3개와 보존 7일로 만든다")
    void deadLetterTopics() throws Exception {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            for (String topic : List.of(REPLICA_DLT, WALLET_DLT)) {
                TopicDescription description = admin.describeTopics(List.of(topic))
                        .allTopicNames().get(10, TimeUnit.SECONDS).get(topic);
                ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
                Config config = admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS).get(resource);

                assertThat(description.partitions()).hasSize(3);
                assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value())
                        .isEqualTo(String.valueOf(Duration.ofDays(7).toMillis()));
            }
        }
        assertThat(REPLICA_DLT).isEqualTo("member.events.v1.billing-app.member-replica.DLT");
        assertThat(WALLET_DLT).isEqualTo("member.events.v1.billing-app.wallet-creation.DLT");
    }

    private static UserDto user(long id, String nickname, Role role, long version) {
        return new UserDto(id, nickname, role, UserStatus.ACTIVE, version);
    }

    private int walletCount(long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.wallet WHERE user_id = ?", Integer.class, userId);
    }

    private int replicaCount(long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.billing_user WHERE id = ?", Integer.class, userId);
    }

    /** 회원 앱처럼 레코드를 JSON으로 싣고 클래스 이름을 타입 헤더({@code __TypeId__})로 붙인다. */
    private void send(long userId, Object event) {
        try (Producer<String, Object> producer = new KafkaProducer<>(producerProperties(),
                new StringSerializer(), new JacksonJsonSerializer<>())) {
            producer.send(new ProducerRecord<>(BillingKafkaConfig.MEMBER_EVENTS_TOPIC, String.valueOf(userId), event))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendRaw(long userId, byte[] value, String typeId) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                BillingKafkaConfig.MEMBER_EVENTS_TOPIC, String.valueOf(userId), value);
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

    private List<ConsumerRecord<String, byte[]>> awaitDeadLettersOf(String topic, long userId) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        await().atMost(DEAD_LETTER_TIMEOUT).untilAsserted(() -> {
            found.clear();
            found.addAll(deadLettersOf(topic, userId, Duration.ofSeconds(2)));
            assertThat(found).isNotEmpty();
        });
        return found;
    }

    /** DLT를 처음부터 읽어 이 회원 key의 메시지만 모은다. */
    private List<ConsumerRecord<String, byte[]>> deadLettersOf(String topic, long userId, Duration pollFor) {
        Map<String, Object> properties = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (Consumer<String, byte[]> consumer = new DefaultKafkaConsumerFactory<>(properties,
                new StringDeserializer(), new ByteArrayDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + pollFor.toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).records(topic).forEach(record -> {
                    if (String.valueOf(userId).equals(record.key())) {
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

    /** 원본 메시지가 토픽에 들어간 시각부터 DLT에 들어간 시각까지 */
    private static Duration delayUntilDeadLetter(ConsumerRecord<String, byte[]> deadLetter) {
        long original = ByteBuffer.wrap(deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP).value())
                .getLong();
        return Duration.ofMillis(deadLetter.timestamp() - original);
    }
}
