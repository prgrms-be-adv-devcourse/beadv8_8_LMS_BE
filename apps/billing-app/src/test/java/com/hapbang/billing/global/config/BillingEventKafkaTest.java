package com.hapbang.billing.global.config;

import static com.hapbang.billing.global.config.BillingEventExternalizationConfig.EVENT_TYPE;
import static com.hapbang.billing.global.config.BillingEventExternalizationConfig.EVENT_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.app.BillingEventRepublishUseCase;
import com.hapbang.billing.app.HoldFacade;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.PaymentHoldCommand;
import com.hapbang.billing.app.PaymentPayoutCommand;
import com.hapbang.billing.app.PayoutResult;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.event.BillingEventTopic;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * 지갑 이벤트가 Testcontainers Kafka 토픽에 나가는지 확인한다 (#497, 규칙은 kafka.md #339).
 * <ul>
 *     <li>다른 지갑 테스트는 Kafka 외부화를 끄고(hapbang-module-test.yaml) 발행 기록({@code ApplicationEvents})만 본다. 이 테스트만 켠다.</li>
 *     <li>대표로 지급 완료 이벤트를 실제 지급으로 내보내고, 롤백·재발행은 이벤트를 직접 발행해 본다. 나머지 이벤트의 발행 시점·내용은
 *     각 흐름의 통합 테스트(충전·지급·환불·환전)가 본다.</li>
 *     <li>테스트마다 새 소비자 그룹을 토픽 끝으로 옮긴 뒤 읽고, 그중 이 테스트가 만든 key의 메시지만 본다.</li>
 *     <li>외부화를 켠 설정이라 컨텍스트를 따로 띄우고, 끝나면 닫아 다른 컨텍스트와 합쳐 테스트 DB 연결 상한을 넘지 않게 한다.</li>
 * </ul>
 */
@IntegrationTest
@Import(KafkaTestcontainersConfig.class)
@TestPropertySource(properties = {"spring.modulith.events.externalization.enabled=true",
        "spring.kafka.admin.auto-create=true", "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=1"})
@WithMockUser(roles = "INTERNAL")
@DirtiesContext
class BillingEventKafkaTest {

    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** 더 오는 메시지가 없는지 확인할 때 계속 읽는 시간 */
    private static final Duration HOLD = Duration.ofMillis(500);

    /** 직접 발행하는 이벤트의 충전 ID. 실제 충전 건과 겹치지 않는다 */
    private static final AtomicLong UNSAVED_ID = new AtomicLong(900_000_000L);

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    HoldFacade holdFacade;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    BillingEventRepublishUseCase billingEventRepublishUseCase;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @MockitoSpyBean
    KafkaTemplate<?, ?> kafkaTemplate;

    KafkaConsumer<String, String> consumer;

    final List<ConsumerRecord<String, String>> received = new CopyOnWriteArrayList<>();

    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "billing-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"),
                new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(BillingEventTopic.NAME));
        // 파티션을 받을 때까지 읽은 뒤 끝으로 옮긴다. 이후 이 테스트가 보낸 메시지만 받는다
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
    @DisplayName("토픽 billing.events.v1은 앱이 뜰 때 partition 3개로 선언한다")
    void topicDeclared() {
        assertThat(kafkaAdmin.describeTopics(BillingEventTopic.NAME).get(BillingEventTopic.NAME).partitions())
                .hasSize(BillingEventExternalizationConfig.PARTITIONS);
    }

    @Test
    @DisplayName("지급하면(커밋) key settlement:<지급 건 ID>로 지급 완료 이벤트가 나가고, 본문은 shared 레코드 그대로, 헤더는 종류·버전 두 개다")
    void payoutCompleted() {
        Long host = wallet(1L);
        wallet(2L);
        revenue(host, 5_000L);
        holdFacade.hold(new PaymentHoldCommand(1L, BillingReferenceType.APPEARANCE_REQUEST, 7L, 4_000L));

        PayoutResult paid = holdFacade.payout(PaymentPayoutCommand.full(1L, BillingReferenceType.APPEARANCE_REQUEST,
                7L, "P1", 2L, SettlementSourceType.APPEARANCE_REQUEST, 7001L));

        ConsumerRecord<String, String> record = awaitExactly("settlement:" + paid.settlementId(), 1).getFirst();
        assertThat(header(record, EVENT_TYPE)).isEqualTo("PayoutCompletedEvent");
        assertThat(header(record, EVENT_VERSION)).isEqualTo("1");
        assertThat(header(record, "eventId")).isNull();
        // 받는 쪽 JSON 역직렬화기가 타입을 고르는 헤더. JSON 직렬화기가 붙인다
        assertThat(header(record, "__TypeId__")).isEqualTo(PayoutCompletedEvent.class.getName());
        PayoutCompletedEvent event = jsonMapper.readValue(record.value(), PayoutCompletedEvent.class);
        assertThat(event).extracting(PayoutCompletedEvent::settlementId, PayoutCompletedEvent::receiverUserId,
                PayoutCompletedEvent::payoutType, PayoutCompletedEvent::sourceType, PayoutCompletedEvent::sourceId,
                PayoutCompletedEvent::paidAmountHp)
                .containsExactly(paid.settlementId(), 2L, PayoutType.NORMAL, SettlementSourceType.APPEARANCE_REQUEST,
                        7001L, 4_000L);
        assertThat(event.paidAt()).isNotNull();
    }

    @Test
    @DisplayName("이벤트를 발행한 트랜잭션이 롤백되면 Kafka로 나가지 않는다")
    void notOnRollback() {
        ChargeCompletedEvent rolledBack = chargeCompleted(UNSAVED_ID.incrementAndGet());
        ChargeCompletedEvent committed = chargeCompleted(UNSAVED_ID.incrementAndGet());

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(rolledBack);
            status.setRollbackOnly();
        });
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(committed));

        // 뒤에 커밋한 이벤트가 도착할 때까지 기다린 뒤, 롤백한 이벤트는 없는지 잠시 더 읽으며 본다(key가 달라 partition이 다를 수 있다)
        awaitRecords(chargeKey(committed), 1);
        await().during(HOLD).atMost(TIMEOUT).pollInSameThread()
                .untilAsserted(() -> assertThat(records(chargeKey(rolledBack))).isEmpty());
    }

    @Test
    @DisplayName("Kafka 응답을 받지 못해 발행 기록이 미완료로 남으면, 유예 시간이 지난 뒤 재발행 배치가 같은 key·같은 내용으로 한 번 더 보낸다")
    void republish() {
        // 첫 보내기는 브로커에 도착하지만 지갑 앱은 실패로 받는다(응답 유실). 다음부터는 그대로 보낸다
        doAnswer(invocation -> {
            ((CompletableFuture<?>) invocation.callRealMethod()).join();
            return CompletableFuture.failedFuture(new KafkaException("응답 유실 흉내"));
        }).doCallRealMethod().when(kafkaTemplate).send(any(Message.class));

        ChargeCompletedEvent event = chargeCompleted(UNSAVED_ID.incrementAndGet());
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
        String key = chargeKey(event);
        awaitRecords(key, 1);

        // 유예 시간(1분)이 지난 미완료 기록을 재발행 배치가 다시 보낸다
        assertThat(billingEventRepublishUseCase.republishDue(Instant.now().plus(Duration.ofMinutes(2)))).isEqualTo(1);
        List<ConsumerRecord<String, String>> records = awaitExactly(key, 2);

        // 재발행이 성공하면 완료로 남아, 다시 실행해도 또 보내지 않는다
        assertThat(billingEventRepublishUseCase.republishDue(Instant.now().plus(Duration.ofMinutes(4)))).isZero();
        awaitExactly(key, 2);

        // 받는 쪽은 (eventType, 충전 ID)로 두 번째를 거른다
        assertThat(records).extracting(record -> header(record, EVENT_TYPE)).containsOnly("ChargeCompletedEvent");
        assertThat(records).extracting(ConsumerRecord::value).containsOnly(records.getFirst().value());
    }

    // ---------------------------------------------------------------------------------------------

    private static ChargeCompletedEvent chargeCompleted(Long chargeId) {
        return new ChargeCompletedEvent(chargeId, 1L, 10_000L, 10_000L, T1);
    }

    private static String chargeKey(ChargeCompletedEvent event) {
        return "point-charge:" + event.chargeId();
    }

    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 수익 HP 가용 잔액을 늘리는 지급 원장을 기록한다.
    private void revenue(Long walletId, long amountHp) {
        int id = sequence.incrementAndGet();
        ledgerRecordUseCase.record(new LedgerOperation("PAYOUT:" + id, LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, (long) id, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
    }

    private List<ConsumerRecord<String, String>> awaitRecords(String key, int count) {
        await().atMost(TIMEOUT).pollInSameThread()
                .untilAsserted(() -> assertThat(records(key)).hasSizeGreaterThanOrEqualTo(count));
        return records(key);
    }

    /** 이 key의 메시지가 count건 온 뒤, 잠시 더 읽어도 그대로 count건인지 (중복 발행이 없는지) */
    private List<ConsumerRecord<String, String>> awaitExactly(String key, int count) {
        awaitRecords(key, count);
        await().during(HOLD).atMost(TIMEOUT).pollInSameThread()
                .untilAsserted(() -> assertThat(records(key)).hasSize(count));
        return records(key);
    }

    /** 지금까지 받은 메시지 중 이 key의 것. 부를 때마다 새로 온 메시지를 더 읽는다 */
    private List<ConsumerRecord<String, String>> records(String key) {
        consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        return received.stream()
                .filter(record -> key.equals(record.key()))
                .toList();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
