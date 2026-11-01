package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.EventPublication.Status;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;

import tools.jackson.databind.json.JsonMapper;

/**
 * 회원 이벤트 Kafka 외부화의 발행 기록 보존과 복구.
 * <ul>
 *     <li>회원 앱은 {@link MemberApp} 설정으로 이 테스트가 직접 띄우고 닫는다. 닫았다가 새로 띄우는 것이 앱 재기동이다.
 *     재기동 재발행은 운영 YAML과 같이 켠다. 모듈 테스트 설정이라 운영 application.yaml 자체는 읽지 않는다.</li>
 *     <li>broker는 이 테스트 전용 Kafka 컨테이너다. 실제로 죽였다가 같은 컨테이너를 다시 띄워 공통 컨테이너를 건드리지 않는다.
 *     재시작해도 주소가 같도록 호스트 포트를 고정한다.</li>
 *     <li>테스트 클래스 자신의 컨텍스트(다른 통합 테스트와 같은 공통 Kafka 컨테이너)는 회원 앱 밖에서 DB를 확인하는 용도다.
 *     띄웠다 닫는 회원 앱만 이 테스트 전용 broker로 보낸다.</li>
 *     <li>broker 장애가 빨리 실패로 끝나도록 producer 대기 시간을 줄인다(운영 기본값 max.block 60초·delivery 120초).
 *     실패 뒤 동작은 기본값과 같다.</li>
 * </ul>
 */
@MemberIntegrationTest
class MemberEventKafkaRecoveryIntegrationTest {

    private static final String TOPIC = "member.events.v1";
    private static final String TYPE_ID_HEADER = "__TypeId__";
    private static final String EXTERNALIZER = "EventExternalizerModuleListener";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final int KAFKA_HOST_PORT = freePort();
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<TestContext> runningApps = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Consumer<String, byte[]>> consumers = new ArrayList<>();

    @Autowired
    UserRepository userRepository;

    @Autowired
    EventPublicationRepository eventPublicationRepository;

    @BeforeAll
    static void startKafka() {
        KAFKA.setPortBindings(List.of(KAFKA_HOST_PORT + ":9092"));
        KAFKA.start();
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.stop();
    }

    @BeforeEach
    void ensureBrokerRunning() {
        if (!brokerRunning()) {
            startBroker();
        }
    }

    /**
     * 다른 테스트가 남긴 미완료 발행 기록까지 모두 지운다. 남아 있으면 이 테스트가 띄우는 앱이 재기동 재발행으로
     * 그 기록을 다시 보내고, 전송이 끝나지 않는 앱에서는 스케줄러 스레드가 막힌다. 테스트는 한 번에 하나씩 실행된다.
     */
    @BeforeEach
    void deleteIncompletePublications() {
        eventPublicationRepository.deletePublications(eventPublicationRepository.findIncompletePublications().stream()
                .map(TargetEventPublication::getIdentifier)
                .toList());
    }

    @AfterEach
    void cleanUp() {
        consumers.forEach(Consumer::close);
        runningApps.forEach(this::stop);
        // 실패한 테스트가 남긴 이 테스트 회원의 발행 기록이 다음 재기동에서 다시 나가지 않게 지운다
        eventPublicationRepository.deletePublications(eventPublicationRepository.findIncompletePublications().stream()
                .filter(this::ofThisTest)
                .map(TargetEventPublication::getIdentifier)
                .toList());
    }

    @Test
    @DisplayName("업무 커밋 직후 전송이 끝나기 전에 앱이 멈춰도 회원 변경과 발행 기록이 남고, 새로 띄운 앱이 다시 발행한다")
    void republishAfterRestartWhenStoppedRightAfterCommit() {
        User user = save();
        // 아무도 듣지 않는 주소라 커밋 뒤 전송이 PROCESSING에서 멈춘다. 이 앱은 다시는 보낼 수 없으니 죽은 프로세스와 같다.
        // 정상 종료는 진행 중 전송을 기다렸다가 FAILED로 기록하므로, 강제 종료(kill)처럼 상태를 남기려고 닫지 않고 둔다
        TestContext stuck = start(StuckMemberApp.class, "127.0.0.1:" + freePort());

        facade(stuck).updateProfile(user.getId(), "종료전수정");

        assertThat(nickname(user)).isEqualTo("종료전수정");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(externalizations(user)).singleElement()
                .satisfies(publication -> assertThat(publication.getStatus()).isEqualTo(Status.PROCESSING)));

        start(MemberApp.class, KAFKA.getBootstrapServers());

        ConsumerRecord<String, byte[]> record = awaitSingleRecord(user, "종료전수정");
        assertThat(typeId(record)).isEqualTo(UserModifiedEvent.class.getName());
        assertThat(jsonMapper.readValue(record.value(), UserModifiedEvent.class))
                .isEqualTo(new UserModifiedEvent(find(user).toDto()));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(externalizations(user)).isEmpty());
    }

    @Test
    @DisplayName("broker만 복구하면 실행 중인 앱은 실패한 발행을 스스로 다시 보내지 않고, 기록만 남긴다")
    void brokerOnlyRecoveryDoesNotRepublishWhileRunning() {
        User user = save();
        TestContext app = start(MemberApp.class, KAFKA.getBootstrapServers());
        // 정상 동작 중인 앱(토픽 메타데이터를 이미 가진 producer)에서 장애를 시작한다
        facade(app).updateProfile(user.getId(), "장애전수정");
        awaitSingleRecord(user, "장애전수정");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(externalizations(user)).isEmpty());

        stopBroker();
        facade(app).updateProfile(user.getId(), "장애중수정");

        // broker가 없어도 회원 변경은 커밋되고, 전송 실패는 발행 기록(FAILED)으로 남는다
        assertThat(nickname(user)).isEqualTo("장애중수정");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(externalizations(user)).singleElement()
                .satisfies(publication -> assertThat(publication.getStatus()).isEqualTo(Status.FAILED)));

        startBroker();

        // 실행 중 재시도·stale 회수 설정이 없어 broker가 돌아와도 보내지 않는다 (final-design 5.2)
        Consumer<String, byte[]> consumer = subscribe();
        List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
        await().during(Duration.ofSeconds(15)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            received.addAll(poll(consumer, user, Duration.ofMillis(250)));
            assertThat(received).extracting(this::nicknameOf).doesNotContain("장애중수정");
        });
        // 같은 소비자가 장애 전 레코드는 읽었으니, 못 받은 것은 소비자 문제가 아니라 보내지 않아서다
        assertThat(received).extracting(this::nicknameOf).containsExactly("장애전수정");
        assertThat(externalizations(user)).singleElement()
                .satisfies(publication -> assertThat(publication.getStatus()).isEqualTo(Status.FAILED));

        // 기록은 그대로라 누군가 재제출하면 그때 나간다. 지금은 재기동 재발행 외에 이 호출을 하는 운영 코드가 없다
        app.getApplicationContext().getBean(IncompleteEventPublications.class)
                .resubmitIncompletePublications(this::ofThisTest);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            received.addAll(poll(consumer, user, Duration.ofMillis(250)));
            assertThat(received).extracting(this::nicknameOf).containsExactly("장애전수정", "장애중수정");
        });
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(externalizations(user)).isEmpty());
    }

    @Test
    @DisplayName("broker 장애 중 실패한 발행은 broker 복구 뒤 앱을 재기동하면 다시 발행된다")
    void republishAfterBrokerAndAppRestart() {
        User user = save();
        TestContext app = start(MemberApp.class, KAFKA.getBootstrapServers());

        stopBroker();
        facade(app).updateProfile(user.getId(), "재기동복구");

        assertThat(nickname(user)).isEqualTo("재기동복구");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(externalizations(user)).singleElement()
                .satisfies(publication -> assertThat(publication.getStatus()).isEqualTo(Status.FAILED)));

        stop(app);
        startBroker();
        start(MemberApp.class, KAFKA.getBootstrapServers());

        ConsumerRecord<String, byte[]> record = awaitSingleRecord(user, "재기동복구");
        assertThat(jsonMapper.readValue(record.value(), UserModifiedEvent.class))
                .isEqualTo(new UserModifiedEvent(find(user).toDto()));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(externalizations(user)).isEmpty());
    }

    /** 운영 설정의 재기동 재발행을 켠 회원 앱. 보낼 broker 주소는 띄울 때 정한다 */
    @IntegrationTest
    @Import(TargetBroker.class)
    @TestPropertySource(properties = {
            "spring.modulith.events.republish-outstanding-events-on-restart=true",
            "spring.kafka.producer.properties.max.block.ms=3000",
            "spring.kafka.producer.properties.request.timeout.ms=1000",
            "spring.kafka.producer.properties.delivery.timeout.ms=3000"
    })
    static class MemberApp {
    }

    /** 전송이 끝나지 않는 회원 앱. 닫을 때 진행 중 전송을 1초만 기다린다 */
    @IntegrationTest
    @Import(TargetBroker.class)
    @TestPropertySource(properties = {
            "spring.modulith.events.republish-outstanding-events-on-restart=true",
            "spring.kafka.producer.properties.max.block.ms=600000",
            // 아무도 듣지 않는 주소라 기동 때 토픽 선언(KafkaAdmin)이 제한 시간까지 기다린다. 이 앱은 토픽이 필요 없다
            "spring.kafka.admin.auto-create=false",
            "spring.lifecycle.timeout-per-shutdown-phase=1s"
    })
    static class StuckMemberApp {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TargetBroker {

        static volatile String bootstrapServers;

        @Bean
        DynamicPropertyRegistrar targetBrokerProperties() {
            return registry -> registry.add("spring.kafka.bootstrap-servers", () -> bootstrapServers);
        }
    }

    private TestContext start(Class<?> appClass, String bootstrapServers) {
        TargetBroker.bootstrapServers = bootstrapServers;
        TestContext app = new TestContextManager(appClass).getTestContext();
        app.getApplicationContext();
        runningApps.add(app);
        return app;
    }

    private void stop(TestContext app) {
        if (app.hasApplicationContext()) {
            app.markApplicationContextDirty(null);
        }
    }

    private MemberFacade facade(TestContext app) {
        ApplicationContext context = app.getApplicationContext();
        return context.getBean(MemberFacade.class);
    }

    private static void stopBroker() {
        DockerClientFactory.lazyClient().killContainerCmd(KAFKA.getContainerId()).exec();
        await().atMost(TIMEOUT).until(() -> !brokerRunning());
    }

    private static void startBroker() {
        DockerClientFactory.lazyClient().startContainerCmd(KAFKA.getContainerId()).exec();
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3_000))) {
            await().atMost(TIMEOUT).ignoreExceptions()
                    .until(() -> !admin.describeCluster().nodes().get(3, TimeUnit.SECONDS).isEmpty());
        }
    }

    private static boolean brokerRunning() {
        return Boolean.TRUE.equals(DockerClientFactory.lazyClient().inspectContainerCmd(KAFKA.getContainerId())
                .exec().getState().getRunning());
    }

    private Consumer<String, byte[]> subscribe() {
        Consumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "member-event-recovery-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(TOPIC));
        consumers.add(consumer);
        return consumer;
    }

    /** 이 회원의 레코드가 nickname을 담아 한 번 도착하고, 잠시 더 읽어도 그 하나뿐인지 확인한다 */
    private ConsumerRecord<String, byte[]> awaitSingleRecord(User user, String nickname) {
        Consumer<String, byte[]> consumer = subscribe();
        List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
        await().atMost(TIMEOUT).untilAsserted(() -> {
            received.addAll(poll(consumer, user, Duration.ofMillis(250)));
            assertThat(received).isNotEmpty();
        });
        received.addAll(poll(consumer, user, Duration.ofSeconds(2)));
        assertThat(received).extracting(this::nicknameOf).containsExactly(nickname);
        return received.getFirst();
    }

    private List<ConsumerRecord<String, byte[]>> poll(Consumer<String, byte[]> consumer, User user, Duration duration) {
        List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
        consumer.poll(duration).records(TOPIC).forEach(record -> {
            if (user.getId().toString().equals(record.key())) {
                received.add(record);
            }
        });
        return received;
    }

    private String nicknameOf(ConsumerRecord<String, byte[]> record) {
        return jsonMapper.readValue(record.value(), UserModifiedEvent.class).user().nickname();
    }

    private String typeId(ConsumerRecord<String, byte[]> record) {
        return new String(record.headers().lastHeader(TYPE_ID_HEADER).value(), StandardCharsets.UTF_8);
    }

    /** 이 회원 변경의 Kafka 외부화 미완료 발행 기록 */
    private List<TargetEventPublication> externalizations(User user) {
        return eventPublicationRepository.findIncompletePublications().stream()
                .filter(publication -> publication.getTargetIdentifier().getValue().contains(EXTERNALIZER))
                .filter(publication -> publication.getEvent() instanceof UserModifiedEvent modified
                        && modified.user().id().equals(user.getId()))
                .toList();
    }

    private boolean ofThisTest(EventPublication publication) {
        return publication.getEvent() instanceof UserModifiedEvent modified && userIds.contains(modified.user().id());
    }

    private User save() {
        User user = userRepository.save(MemberFixture.user());
        userIds.add(user.getId());
        return user;
    }

    private String nickname(User user) {
        return find(user).getNickname();
    }

    private User find(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
