package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.event.kafka.ChatKafkaConfig;
import com.hapbang.chat.in.event.kafka.ChatKafkaConstants;
import com.hapbang.chat.in.event.kafka.ChatKafkaConsumerSettings;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.support.RealtimeStompClient.Frame;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

import jakarta.persistence.EntityManager;

/**
 * 회원 변경이 실제 경로로 로컬 연결 종료까지 이어지는지 검증한다(회원 이벤트 Kafka 소비·전달 파이프라인·실시간 전파 통합, test-criteria "회원 이벤트").
 * <p>
 * Kafka 회원 변경 이벤트 → 회원 반영과 연결 제어 outbox(같은 트랜잭션) → relay → 제어 Stream → consumer → 제어 채널
 * Pub/Sub → 구독자의 최신 회원 상태 재검사 → 로컬 연결 종료. 회원 이벤트 리스너는 연결을 직접 닫지 않는다.
 * <p>
 * 회원 이벤트를 Kafka 소비로 바꾸면서 인프로세스 리스너 기반으로 남겨 둘 수 없어 지운 {@code ChatStompE2ETest}의 두 테스트(강등 시 모든 연결 종료,
 * 프로필 수정 시 연결 유지)를 이 경로로 되살렸다. 토픽·DLT·소비자 그룹은 이 클래스만 쓰는 이름이다.
 */
@ChatE2ETest
@Import({KafkaTestcontainersConfig.class, ChatMemberControlE2ETest.Topics.class})
class ChatMemberControlE2ETest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "member.events.v1.control-e2e-" + SUFFIX;
    private static final String GROUP = "chat-app-control-e2e-" + SUFFIX;
    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void memberEvents(DynamicPropertyRegistry registry) {
        registry.add(ChatKafkaConstants.MEMBER_EVENTS_ENABLED, () -> "true");
    }

    /** 이 클래스만 쓰는 토픽·그룹·DLT로 회원 이벤트 소비 설정을 바꾼다. 나머지 값은 운영 상수 그대로다 */
    @TestBean(name = ChatKafkaConfig.MEMBER_EVENT_SETTINGS)
    private ChatKafkaConsumerSettings memberEventSettings;

    static ChatKafkaConsumerSettings memberEventSettings() {
        return new ChatKafkaConsumerSettings(TOPIC, GROUP, TOPIC + "." + GROUP + ".DLT", ChatKafkaConstants.MAX_RETRIES,
                ChatKafkaConstants.RETRY_INITIAL_INTERVAL, ChatKafkaConstants.RETRY_MAX_INTERVAL,
                ChatKafkaConstants.DEAD_LETTER_SEND_TIMEOUT, ChatKafkaConstants.DEAD_LETTER_PARTITIONS,
                ChatKafkaConstants.DEAD_LETTER_REPLICAS);
    }

    @LocalServerPort
    private int port;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatUserRepository chatUserRepository;

    @Autowired
    private LocalChatConnections localChatConnections;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    private ChatNativeSql sql;

    private RealtimeStompClient stomp;
    private KafkaProducer<String, byte[]> producer;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void setUp() {
        assertThat(localChatConnections.connectedUserIds()).isEmpty();
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        stomp = new RealtimeStompClient(RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(),
                port, localChatConnections);
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
    }

    @AfterEach
    void tearDown() {
        producer.close();
        stomp.close();
    }

    @Test
    void 채팅할_수_없게_강등되면_회원_변경_이벤트에서_시작한_제어_경로로_그_회원의_모든_연결이_닫힌다() throws Exception {
        StompSession first = stomp.connect(HOST_ID);
        StompSession second = stomp.connect(HOST_ID);
        StompSession other = stomp.connect(GUEST_ID);

        List<CloseStatus> closeStatuses = closeStatusesOf(HOST_ID, 2,
                () -> modified(HOST_ID, "호스트", Role.USER, UserStatus.ACTIVE, 1L));

        assertThat(closeStatuses).allSatisfy(closeStatus -> {
            assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
            assertThat(closeStatus.getReason()).isEqualTo("CHAT_NOT_ALLOWED");
        });
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !first.isConnected() && !second.isConnected());
        assertThat(other.isConnected()).isTrue();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> localChatConnections.connectedUserIds().equals(Set.of(GUEST_ID)));
        // 연결은 회원 이벤트 리스너가 아니라 제어 outbox를 거친 경로로 닫혔다
        assertThat(controlRows(HOST_ID)).isEqualTo(1);
    }

    // 회원 변경 이벤트는 프로필 수정에도 온다. 채팅할 수 있는 회원의 프로필 수정으로 연결을 닫지 않는다
    @Test
    void 프로필_수정처럼_채팅_가능_여부가_그대로인_변경은_연결을_유지한다() throws Exception {
        StompSession host = stomp.connect(HOST_ID);

        modified(HOST_ID, "새닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 1L);

        await().atMost(PIPELINE_TIMEOUT).until(() -> chatUserRepository.findById(HOST_ID)
                .map(user -> user.getNickname().equals("새닉네임")).orElse(false));
        assertThat(controlRows(HOST_ID)).isZero();
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(host::isConnected);
    }

    /**
     * 통합 검증 보강: 정지도 강등과 같은 실제 경로(Kafka → 제어 outbox → Stream → Pub/Sub → RECHECK)로 대상 회원의 모든 로컬
     * 연결만 닫는다. 같은 앱에 연결된 다른 회원(여러 기기 포함)의 연결은 남고, 그 뒤에도 메시지 파이프라인으로 계속 받는다.
     */
    @Test
    void 정지되면_제어_경로로_대상_회원의_모든_연결만_닫히고_다른_회원의_여러_연결은_남아_계속_메시지를_받는다() throws Exception {
        RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        Long chatRoomId = createRoom(client, HOST_ID, GUEST_ID);
        StompSession target1 = stomp.connect(OTHER_INFLUENCER_ID);
        StompSession target2 = stomp.connect(OTHER_INFLUENCER_ID);
        StompSession host1 = stomp.connect(HOST_ID);
        StompSession host2 = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        BlockingQueue<Frame<ChatMessageResponse>> host1Messages = stomp.subscribe(host1,
                "/user/queue/chats/messages", ChatMessageResponse.class);
        BlockingQueue<Frame<ChatMessageResponse>> host2Messages = stomp.subscribe(host2,
                "/user/queue/chats/messages", ChatMessageResponse.class);
        BlockingQueue<Frame<ChatMessageResponse>> guestMessages = stomp.subscribeRoomMessages(guest, chatRoomId, 1);
        waitForSubscriptions();

        List<CloseStatus> closeStatuses = closeStatusesOf(OTHER_INFLUENCER_ID, 2,
                () -> modified(OTHER_INFLUENCER_ID, "다른인플루언서", Role.INFLUENCER, UserStatus.SUSPENDED, 1L));

        assertThat(closeStatuses).allSatisfy(closeStatus -> {
            assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
            assertThat(closeStatus.getReason()).isEqualTo("CHAT_NOT_ALLOWED");
        });
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !target1.isConnected() && !target2.isConnected());
        assertThat(List.of(host1, host2, guest)).allMatch(StompSession::isConnected);
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> localChatConnections.connectedUserIds().equals(Set.of(HOST_ID, GUEST_ID)));
        assertThat(controlRows(OTHER_INFLUENCER_ID)).isEqualTo(1);
        assertThat(controlRows(HOST_ID)).isZero();
        assertThat(controlRows(GUEST_ID)).isZero();

        host1.send("/pub/chats/" + chatRoomId + "/messages", new SendChatMessageRequest("정지와 무관", "after-suspend"));
        for (BlockingQueue<Frame<ChatMessageResponse>> messages : List.of(host1Messages, host2Messages, guestMessages)) {
            Frame<ChatMessageResponse> frame = messages.poll(PIPELINE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(frame).isNotNull();
            assertThat(frame.payload().content()).isEqualTo("정지와 무관");
            assertThat(frame.participationVersion()).isEqualTo("1");
        }
        assertThat(List.of(host1, host2, guest)).allMatch(StompSession::isConnected);
    }

    private Long createRoom(RestTestClient client, Long userId, Long targetUserId) {
        RsData<ChatRoomResponse> room = client.post().uri("/api/v1/chats")
                .headers(E2ESession.login(client, userId, Role.INFLUENCER)::applyTo)
                .body(new CreateChatRoomRequest(targetUserId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<RsData<ChatRoomResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(room).isNotNull();
        return room.data().chatRoomId();
    }

    private void modified(long id, String nickname, Role role, UserStatus status, Long version) {
        String json = "{\"user\":{\"id\":" + id + ",\"nickname\":\"" + nickname + "\",\"role\":\"" + role.name()
                + "\",\"status\":\"" + status.name() + "\",\"version\":" + version + "}}";
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, String.valueOf(id),
                json.getBytes(StandardCharsets.UTF_8));
        record.headers().add("__TypeId__", UserModifiedEvent.class.getName().getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("회원 이벤트를 보내지 못했습니다.", exception);
        }
    }

    private int controlRows(long userId) {
        Integer rows = sql.single("select count(*) from chats.chat_control_outbox where user_id = :userId",
                Integer.class, Map.of("userId", userId));
        return rows == null ? 0 : rows;
    }

    /**
     * 동작을 실행한 뒤 그 회원의 연결이 서버에서 정확히 {@code expected}개 닫히는지 확인한다. 전달 파이프라인을 거치므로 넉넉히
     * 기다리고, 기대한 수만큼 닫힌 뒤에도 잠깐 관찰해 더 닫히지 않는지 본다.
     */
    private List<CloseStatus> closeStatusesOf(Long userId, int expected, Runnable action) throws InterruptedException {
        BlockingQueue<CloseStatus> disconnects = new LinkedBlockingQueue<>();
        ApplicationListener<SessionDisconnectEvent> listener = event -> {
            if (event.getUser() != null && String.valueOf(userId).equals(event.getUser().getName())) {
                disconnects.add(event.getCloseStatus());
            }
        };
        applicationContext.addApplicationListener(listener);
        try {
            action.run();
            List<CloseStatus> closeStatuses = new ArrayList<>();
            for (int i = 0; i < expected; i++) {
                CloseStatus closeStatus = disconnects.poll(PIPELINE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                assertThat(closeStatus).as("%d번째 연결 종료", i + 1).isNotNull();
                closeStatuses.add(closeStatus);
            }
            assertThat(disconnects.poll(300, TimeUnit.MILLISECONDS)).as("기대보다 많은 연결 종료").isNull();
            return closeStatuses;
        } finally {
            applicationContext.removeApplicationListener(listener);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic chatMemberControlE2eTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
