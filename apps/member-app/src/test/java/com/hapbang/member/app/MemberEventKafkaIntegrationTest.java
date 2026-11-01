package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicDescription;
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
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 회원 가입·수정 이벤트를 Kafka {@code member.events.v1}로 외부화한다.
 * 토픽은 {@code @Externalized}로 고정이고 다른 통합 테스트도 같은 컨테이너의 같은 토픽에 보낸다.
 * 그래서 테스트마다 고유한 소비자 그룹으로 처음부터 읽고 이 테스트가 만든 회원 ID(key)로 거른다.
 * 회원 ID는 테스트 JVM 안에서 다시 쓰이지 않는다(DB 정리가 ID를 초기화하지 않는다).
 */
@MemberIntegrationTest
class MemberEventKafkaIntegrationTest {

    private static final String TOPIC = "member.events.v1";
    private static final String TYPE_ID_HEADER = "__TypeId__";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Modulith가 Kafka로 보내는 리스너(EventExternalizerModuleListener)의 발행 대상 이름 */
    private static final String EXTERNALIZER = "EventExternalizerModuleListener";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EventPublicationRepository eventPublicationRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private String group;
    private Consumer<String, byte[]> consumer;

    @BeforeEach
    void subscribe() {
        group = "member-event-test-" + UUID.randomUUID();
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
    @DisplayName("가입하면 회원 ID를 key로 UserJoinedEvent 레코드가 JSON 그대로 발행된다")
    void join() {
        String sub = UUID.randomUUID().toString();
        User user = memberFacade.oauthLogin(OAuthProvider.GOOGLE, sub, sub + "@example.com", "카프카가입");

        ConsumerRecord<String, byte[]> record = awaitRecords(user.getId(), 1).getFirst();

        assertThat(headers(record)).containsExactlyInAnyOrderEntriesOf(Map.of(
                TYPE_ID_HEADER, UserJoinedEvent.class.getName(),
                "eventType", "UserJoinedEvent",
                "eventVersion", "1"));
        assertThat(userJson(record))
                .containsOnlyKeys("id", "nickname", "role", "status", "version")
                .containsEntry("nickname", "카프카가입")
                .containsEntry("role", "USER")
                .containsEntry("status", "ACTIVE");
        // shared 레코드 그대로라 같은 클래스로 되돌리면 원본 회원 정보와 같다
        assertThat(jsonMapper.readValue(record.value(), UserJoinedEvent.class))
                .isEqualTo(new UserJoinedEvent(find(user).toDto()));
    }

    @Test
    @DisplayName("닉네임을 바꾸면 회원 ID를 key로 올라간 버전을 담은 UserModifiedEvent가 발행된다")
    void modify() {
        User user = userRepository.save(MemberFixture.user());

        memberFacade.updateProfile(user.getId(), "카프카수정");

        ConsumerRecord<String, byte[]> record = awaitRecords(user.getId(), 1).getFirst();

        assertThat(headers(record)).containsExactlyInAnyOrderEntriesOf(Map.of(
                TYPE_ID_HEADER, UserModifiedEvent.class.getName(),
                "eventType", "UserModifiedEvent",
                "eventVersion", "1"));
        assertThat(userJson(record))
                .containsEntry("nickname", "카프카수정")
                .containsEntry("version", 1);
        assertThat(jsonMapper.readValue(record.value(), UserModifiedEvent.class))
                .isEqualTo(new UserModifiedEvent(find(user).toDto()));
    }

    @Test
    @DisplayName("발행 기록은 회원 변경과 같은 트랜잭션에 남아, 롤백되면 기록도 메시지도 남지 않는다")
    void sameTransaction() {
        User user = userRepository.save(MemberFixture.user());
        String nickname = user.getNickname();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            memberFacade.updateProfile(user.getId(), "롤백될닉네임");
            // 커밋 전, 업무 트랜잭션 안에서 Kafka 외부화 발행 기록이 이미 보인다
            assertThat(incompletePublications(user.getId()))
                    .filteredOn(publication -> publication.getTargetIdentifier().getValue().contains(EXTERNALIZER))
                    .hasSize(1);
            status.setRollbackOnly();
        });

        assertThat(incompletePublications(user.getId())).isEmpty();
        assertThat(find(user).getNickname()).isEqualTo(nickname);

        // 같은 key는 같은 파티션이라, 롤백된 이벤트가 나갔다면 이 이벤트보다 먼저 읽힌다
        memberFacade.updateProfile(user.getId(), "커밋닉네임");

        assertThat(awaitRecords(user.getId(), 1)).singleElement()
                .satisfies(record -> assertThat(userJson(record)).containsEntry("nickname", "커밋닉네임"));
    }

    /** 이 회원 ID를 key로 가진 레코드를 count개 받을 때까지 읽는다 */
    private List<ConsumerRecord<String, byte[]>> awaitRecords(Long userId, int count) {
        List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
        await().atMost(TIMEOUT).untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(250)).records(TOPIC).forEach(record -> {
                if (userId.toString().equals(record.key())) {
                    received.add(record);
                }
            });
            assertThat(received).hasSizeGreaterThanOrEqualTo(count);
        });
        return received;
    }

    @Test
    @DisplayName("회원 이벤트 토픽은 회원 앱이 partition 3개로 선언해 만든다")
    void topicDeclared() throws Exception {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            TopicDescription topic = admin.describeTopics(List.of(TOPIC)).allTopicNames()
                    .get(10, TimeUnit.SECONDS).get(TOPIC);

            assertThat(topic.partitions()).hasSize(3)
                    .allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
        }
    }

    /** 레코드의 모든 헤더 (값은 UTF-8 문자열). 같은 이름이 두 번 있으면 실패한다 */
    private Map<String, String> headers(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header header : record.headers()) {
            assertThat(headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8)))
                    .as("헤더 %s 중복", header.key()).isNull();
        }
        return headers;
    }

    private Map<String, Object> userJson(ConsumerRecord<String, byte[]> record) {
        Map<String, Map<String, Object>> body = jsonMapper.readValue(record.value(), new TypeReference<>() {
        });
        assertThat(body).containsOnlyKeys("user");
        return body.get("user");
    }

    /** 이 회원의 미완료 UserModifiedEvent 발행 기록 (Kafka 외부화와 세션 권한 갱신 리스너 것이 함께 있다) */
    private List<TargetEventPublication> incompletePublications(Long userId) {
        return eventPublicationRepository.findIncompletePublications().stream()
                .filter(publication -> publication.getEvent() instanceof UserModifiedEvent modified
                        && modified.user().id().equals(userId))
                .toList();
    }

    private User find(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
