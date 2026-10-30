package com.hapbang.appearance.out.event;

import static com.hapbang.appearance.out.event.AppearanceNotificationExternalizationConfig.EVENT_ID;
import static com.hapbang.appearance.out.event.AppearanceNotificationExternalizationConfig.EVENT_TYPE;
import static com.hapbang.appearance.out.event.AppearanceNotificationExternalizationConfig.EVENT_VERSION;
import static com.hapbang.appearance.out.event.AppearanceNotificationExternalizationConfig.TOPIC;
import static com.hapbang.appearance.out.event.AppearanceNotificationExternalizationConfig.eventId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.AppearanceApplicationFacade;
import com.hapbang.appearance.app.AppearanceRecruitFacade;
import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * 출연 알림 이벤트가 Testcontainers Kafka 토픽에 나가는지 확인한다 (#393).
 * <ul>
 *     <li>다른 출연 테스트는 Kafka 외부화를 끄고(hapbang-module-test.yaml), 이 테스트만 켠다.</li>
 *     <li>토픽은 운영과 같은 {@value AppearanceNotificationExternalizationConfig#TOPIC} 하나를 테스트끼리 같이 쓴다.
 *     테스트마다 새 소비자 그룹으로 처음부터 읽고, 이 테스트가 만든 이벤트 ID의 메시지만 본다(ID는 테스트 사이에 초기화되지 않는다).</li>
 *     <li>외부화를 켠 설정이라 컨텍스트를 따로 띄운다. 끝나면 닫아({@link DirtiesContext}) 캐시된 다른 컨텍스트와 합쳐
 *     테스트 DB 연결 상한을 넘지 않게 한다.</li>
 *     <li>보내기는 커밋 뒤 비동기로 일어나므로 제한 시간 안에 기다린다. 소비자는 스레드 안전하지 않아 테스트 스레드에서만 읽는다.</li>
 * </ul>
 */
@AppearanceIntegrationTest
@Import(KafkaTestcontainersConfig.class)
@TestPropertySource(properties = "spring.modulith.events.externalization.enabled=true")
@WithMockUser(roles = "INFLUENCER")
@DirtiesContext
class AppearanceNotificationKafkaTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** 롤백 테스트에서 실제 요청서와 겹치지 않는 ID */
    private static final AtomicLong UNSAVED_ID = new AtomicLong(900_000_000L);

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    IncompleteEventPublications incompleteEventPublications;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    AppearanceTestClock clock;

    @MockitoSpyBean
    KafkaTemplate<?, ?> kafkaTemplate;

    KafkaConsumer<String, String> consumer;

    final List<ConsumerRecord<String, String>> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "appearance-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(TOPIC));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
        received.clear();
    }

    @Test
    @DisplayName("요청서를 보내면(커밋) 게스트 ID를 키로 요청 도착 이벤트가 나가고, 본문은 shared 레코드 그대로, 헤더에 이벤트 ID·종류·버전을 담는다")
    void requestSent() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now()));
        String eventId = eventId(AppearanceRequestSentEvent.class, sent.id());

        ConsumerRecord<String, String> record = awaitRecords(eventId, 1).getFirst();

        assertThat(record.key()).isEqualTo(String.valueOf(GUEST));
        assertThat(header(record, EVENT_TYPE)).isEqualTo("AppearanceRequestSentEvent");
        assertThat(header(record, EVENT_VERSION)).isEqualTo("1");
        AppearanceRequestDto request = jsonMapper.readValue(record.value(), AppearanceRequestSentEvent.class).request();
        assertThat(request).isEqualTo(new AppearanceRequestDto(sent.id(), HOST, GUEST, sent.title(),
                AppearanceRequestFixture.FEE, sent.scheduledAt(), sent.acceptDeadline()));
    }

    @Test
    @DisplayName("지원하면(커밋) 호스트 ID를 키로 지원 도착 이벤트가 나간다")
    void applicationSubmitted() {
        AppearanceRecruitResDto recruit = appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(clock.now()));
        AppearanceApplicationResDto applied = appearanceApplicationFacade.apply(GUEST, recruit.id(), "지원합니다.");
        String eventId = eventId(AppearanceApplicationSubmittedEvent.class, applied.id());

        ConsumerRecord<String, String> record = awaitRecords(eventId, 1).getFirst();

        assertThat(record.key()).isEqualTo(String.valueOf(HOST));
        assertThat(header(record, EVENT_TYPE)).isEqualTo("AppearanceApplicationSubmittedEvent");
        AppearanceApplicationDto application = jsonMapper.readValue(record.value(),
                AppearanceApplicationSubmittedEvent.class).application();
        assertThat(application.applicationId()).isEqualTo(applied.id());
        assertThat(application.recruitId()).isEqualTo(recruit.id());
        assertThat(application.recruitTitle()).isEqualTo(recruit.title());
        assertThat(application.hostId()).isEqualTo(HOST);
        assertThat(application.guestId()).isEqualTo(GUEST);
        assertThat(application.appliedAt()).isNotNull();
    }

    @Test
    @DisplayName("이벤트를 발행한 트랜잭션이 롤백되면 Kafka로 나가지 않는다")
    void notOnRollback() {
        AppearanceRequestSentEvent rolledBack = requestSentEvent(UNSAVED_ID.incrementAndGet());
        AppearanceRequestSentEvent committed = requestSentEvent(UNSAVED_ID.incrementAndGet());

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(rolledBack);
            status.setRollbackOnly();
        });
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(committed));

        // 뒤에 커밋한 이벤트가 도착할 때까지 기다린 뒤, 롤백한 이벤트는 없는지 본다
        awaitRecords(eventId(AppearanceRequestSentEvent.class, committed.request().requestId()), 1);
        assertThat(records(eventId(AppearanceRequestSentEvent.class, rolledBack.request().requestId()))).isEmpty();
    }

    @Test
    @DisplayName("Kafka 응답을 받지 못해 발행 기록이 미완료로 남으면, 다시 보낼 때(서버 재시작과 같은 재발행) 같은 이벤트 ID로 한 번 더 나간다")
    void republishWithSameEventId() {
        // 첫 보내기는 브로커에 도착하지만 출연 앱은 실패로 받는다(응답 유실). 다음부터는 그대로 보낸다
        doAnswer(invocation -> {
            ((CompletableFuture<?>) invocation.callRealMethod()).join();
            return CompletableFuture.failedFuture(new KafkaException("응답 유실 흉내"));
        }).doCallRealMethod().when(kafkaTemplate).send(any(Message.class));

        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now()));
        String eventId = eventId(AppearanceRequestSentEvent.class, sent.id());
        awaitRecords(eventId, 1);

        // 실패 처리가 끝나 미완료로 남은 기록을 다시 보낸다 (republish-outstanding-events-on-restart와 같은 경로)
        await().atMost(TIMEOUT).pollInSameThread().untilAsserted(() -> {
            incompleteEventPublications.resubmitIncompletePublications(
                    publication -> publication.getEvent() instanceof AppearanceRequestSentEvent);
            assertThat(records(eventId)).hasSizeGreaterThanOrEqualTo(2);
        });

        assertThat(records(eventId)).allSatisfy(record -> {
            assertThat(header(record, EVENT_ID)).isEqualTo(eventId);
            assertThat(record.key()).isEqualTo(String.valueOf(GUEST));
        });
    }

    // ---------------------------------------------------------------------------------------------

    private AppearanceRequestSentEvent requestSentEvent(Long requestId) {
        LocalDateTime now = clock.now();
        return new AppearanceRequestSentEvent(new AppearanceRequestDto(requestId, HOST, GUEST, "롤백 확인",
                AppearanceRequestFixture.FEE, now.plusDays(7), now.plusDays(2)));
    }

    private List<ConsumerRecord<String, String>> awaitRecords(String eventId, int count) {
        await().atMost(TIMEOUT).pollInSameThread().untilAsserted(() -> assertThat(records(eventId)).hasSizeGreaterThanOrEqualTo(count));
        return records(eventId);
    }

    /** 지금까지 받은 메시지 중 이 이벤트 ID의 것. 부를 때마다 새로 온 메시지를 더 읽는다 */
    private List<ConsumerRecord<String, String>> records(String eventId) {
        consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        return received.stream()
                .filter(record -> eventId.equals(header(record, EVENT_ID)))
                .toList();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
