package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.event.UserLoggedOutEvent;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * 로그아웃 이벤트를 Kafka {@code member.events.v1}로 외부화한다 (#539). 실제 Redis(refresh token)와 Kafka로 확인한다.
 * 다른 통합 테스트도 같은 토픽에 보내므로 테스트마다 고유한 소비자 그룹으로 처음부터 읽고 이 테스트의 회원 ID(key)로 거른다
 * ({@link MemberEventKafkaIntegrationTest}와 같은 방식)
 */
@MemberIntegrationTest
@Import(RedisTestcontainersConfig.class)
class MemberLogoutEventKafkaIntegrationTest {

    private static final String TOPIC = "member.events.v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private String group;
    private Consumer<String, byte[]> consumer;
    private final List<String> createdKeys = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        group = "member-logout-event-test-" + UUID.randomUUID();
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(TOPIC));
    }

    @AfterEach
    void close() throws Exception {
        redisTemplate.delete(createdKeys);
        consumer.close();
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // poll 전에 끝난 테스트는 그룹이 만들어지지 않는다
            if (!(e.getCause() instanceof GroupIdNotFoundException)) {
                throw e;
            }
        }
    }

    @Test
    @DisplayName("로그아웃하면 회원 ID를 key로 로그아웃 시각을 담은 UserLoggedOutEvent가 발행된다 (sessionId는 null)")
    void logout() {
        User user = userRepository.save(MemberFixture.user());
        String refreshToken = memberFacade.issueTokens(user.getId(), user.getRole()).refreshToken();
        createdKeys.add("member:user-refresh:" + user.getId());
        createdKeys.add("member:refresh:" + sha256(refreshToken));

        Instant before = Instant.now();
        memberFacade.logout(refreshToken);

        ConsumerRecord<String, byte[]> record = awaitRecord(user.getId());
        assertThat(headers(record)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "__TypeId__", UserLoggedOutEvent.class.getName(),
                "eventType", "UserLoggedOutEvent",
                "eventVersion", "1"));
        UserLoggedOutEvent event = jsonMapper.readValue(record.value(), UserLoggedOutEvent.class);
        assertThat(event.userId()).isEqualTo(user.getId());
        assertThat(event.sessionId()).isNull();
        assertThat(event.loggedOutAt()).isBetween(before, Instant.now());
    }

    /** 이 회원 ID를 key로 가진 레코드를 하나 받을 때까지 읽는다 */
    private ConsumerRecord<String, byte[]> awaitRecord(Long userId) {
        List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
        await().atMost(TIMEOUT).untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(250)).records(TOPIC).forEach(record -> {
                if (userId.toString().equals(record.key())) {
                    received.add(record);
                }
            });
            assertThat(received).isNotEmpty();
        });
        return received.getFirst();
    }

    private static Map<String, String> headers(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
