package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.out.MockPayoutHttpClient;
import com.hapbang.billing.out.MockPayoutScenario;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

// 정산일 자동 송금(#305)을 실제 PostgreSQL과 실제 HTTP(랜덤 포트 서버의 Mock 은행)로 검증한다: 환전 신청(#285) → 관리자 승인(#294, 회차 확정)
// → 정산 실행(ExchangeSettlementUseCase) → 송금·결과 반영(#304) → 결과 확인(recoverStale).
// 배치 클래스(@Scheduled)는 테스트에서 꺼져 있고(공통 스위치) 같은 처리를 UseCase로 직접 호출한다. 시각은 ExchangePayoutClock만 앞당긴다.
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.modulith.events.completion-mode=delete",
                "spring.security.oauth2.client.registration.google.client-id=test",
                "spring.security.oauth2.client.registration.google.client-secret=test",
                "hapbang.storage.s3.endpoint=http://localhost:9",
                "hapbang.storage.s3.bucket=test",
                "hapbang.storage.s3.region=ap-northeast-2",
                "hapbang.storage.s3.key-prefix=test",
                "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY,
                "hapbang.billing.mock-payout.enabled=true",
                "hapbang.billing.mock-payout.connect-timeout=1s",
                "hapbang.billing.mock-payout.read-timeout=1s",
                "hapbang.billing.mock-payout.delay=300ms",
                "hapbang.billing.mock-payout.timeout-delay=2500ms",
                "hapbang.billing.exchange.payout.recovery-after=PT1M",
                "spring.datasource.hikari.maximum-pool-size=6",
                "spring.datasource.hikari.minimum-idle=1"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@ExtendWith(OutputCaptureExtension.class)
class ExchangeSettlementIntegrationTest {

    private static final long INFLUENCER = 2L;
    private static final long ADMIN = 900L;
    private static final String ACCOUNT = "12345678901234";
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Duration STALE = Duration.ofMinutes(2);

    @DynamicPropertySource
    static void cipherProperties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.billing.payment-key.encryption-key", () -> ChargeFixture.ENCRYPTION_KEY);
        registry.add("hapbang.billing.payment-key.hmac-key", () -> ChargeFixture.HMAC_KEY);
    }

    @Autowired
    ExchangeSettlementUseCase settlementUseCase;

    @Autowired
    ExchangePayoutUseCase payoutUseCase;

    @Autowired
    ExchangeRequestUseCase exchangeRequestUseCase;

    @Autowired
    ExchangeReviewUseCase reviewUseCase;

    @Autowired
    BillingUserSyncUseCase billingUserSyncUseCase;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    InternalHoldFacade internalHoldFacade;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    MockPayoutScenarios scenarios;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    ExchangePayoutClock clock;

    @MockitoSpyBean
    ExchangePayoutRequestIds requestIds;

    @MockitoSpyBean
    MockPayoutHttpClient client;

    // 테스트 시각 = 실제 시각 + timeShift. 날짜 경계를 정하는 테스트는 fixed로 고정한다
    private volatile Duration timeShift = Duration.ZERO;
    private volatile Instant fixed;
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        timeShift = Duration.ZERO;
        fixed = null;
        doAnswer(invocation -> fixed != null ? fixed
                : Instant.now().plus(timeShift).truncatedTo(ChronoUnit.MICROS)).when(clock).now();
    }

    @Test
    @DisplayName("정산일이 되면 승인된 환전 여러 건을 송금해 성공 처리한다. 승인 대기 건은 건드리지 않는다. 계좌번호는 로그에 없다")
    void settlesDueApprovedExchanges(CapturedOutput output) {
        Long walletId = influencer(40_000L);
        List<Long> approved = List.of(approvedExchange(10_000L), approvedExchange(10_000L), approvedExchange(5_000L));
        Long requested = request(5_000L);
        plan(MockPayoutScenario.SUCCESS, MockPayoutScenario.SUCCESS, MockPayoutScenario.SUCCESS);
        fixed = scheduledAt(approved.getFirst()).plus(Duration.ofHours(10).plusMinutes(5)); // 정산일 10:05 KST

        ExchangeSettlementRunResult result = settlementUseCase.run();

        assertThat(result).isEqualTo(new ExchangeSettlementRunResult(false, 3, 3, 3, 0, 0, 0, 0, false));
        assertThat(approved).allSatisfy(id -> assertThat(status(id)).isEqualTo("COMPLETED"));
        assertThat(status(requested)).isEqualTo("REQUESTED");
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(3);
        assertThat(captureCount()).isEqualTo(3);
        assertWallet(walletId, 10_000L, 5_000L);
        assertThat(output.getAll()).doesNotContain(ACCOUNT).contains("환전 정산 실행 완료");
    }

    @Test
    @DisplayName("정산 예정 시각 전에는 송금하지 않는다(정산일 전날 23:59:59.999999 KST)")
    void notBeforeSettlementDay() {
        influencer(20_000L);
        Long exchangeId = approvedExchange(10_000L);
        fixed = scheduledAt(exchangeId).minus(1, ChronoUnit.MICROS);

        ExchangeSettlementRunResult result = settlementUseCase.run();

        assertThat(result.targets()).isZero();
        assertThat(status(exchangeId)).isEqualTo("APPROVED");
        assertThat(count("billing.exchange_payout")).isZero();
        assertThat(count("billing.mock_payout_transfer")).isZero();
    }

    @Test
    @DisplayName("같은 날 여러 번 실행해도 다시 송금하지 않고(완료 건은 대상 아님), 확정 실패 건도 자동으로 다시 보내지 않는다")
    void repeatedRunsAndFailedNotResent() {
        Long walletId = influencer(30_000L);
        Long ok = approvedExchange(10_000L);
        Long failing = approvedExchange(10_000L);
        plan(MockPayoutScenario.SUCCESS, MockPayoutScenario.FAILED);
        fixed = scheduledAt(ok).plus(Duration.ofHours(10).plusMinutes(5));

        ExchangeSettlementRunResult first = settlementUseCase.run();
        fixed = fixed.plus(Duration.ofHours(1));
        ExchangeSettlementRunResult second = settlementUseCase.run();
        fixed = fixed.plus(Duration.ofDays(31));
        ExchangeSettlementRunResult nextMonth = settlementUseCase.run();

        assertThat(first).isEqualTo(new ExchangeSettlementRunResult(false, 2, 2, 1, 1, 0, 0, 0, false));
        assertThat(second.targets()).isZero();
        assertThat(nextMonth.targets()).isZero();
        assertThat(status(ok)).isEqualTo("COMPLETED");
        assertThat(status(failing)).isEqualTo("FAILED");
        assertThat(count("billing.exchange_payout")).isEqualTo(2);
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(2);
        assertThat(captureCount()).isEqualTo(1);
        // 확정 실패 건은 보류를 유지한다(관리자가 재송금 또는 최종 실패를 정한다)
        assertThat(holdStatus(failing)).isEqualTo("HELD");
        assertWallet(walletId, 10_000L, 10_000L);
    }

    @Test
    @DisplayName("한 건의 보류 정합성 오류는 그 건만 실패로 세고 나머지를 송금한다. 오류 건은 APPROVED로 남아 다음 실행에서 다시 시도한다")
    void isolatesFailingExchange() {
        Long walletId = influencer(30_000L);
        Long broken = approvedExchange(10_000L);
        Long ok = approvedExchange(10_000L);
        internalHoldFacade.release(InternalReleaseCommand.partial(INFLUENCER, BillingReferenceType.EXCHANGE, broken,
                "TEST-PARTIAL", 1_000L));
        plan(MockPayoutScenario.SUCCESS, MockPayoutScenario.SUCCESS);
        fixed = scheduledAt(ok).plus(Duration.ofHours(10));

        ExchangeSettlementRunResult first = settlementUseCase.run();

        assertThat(first).isEqualTo(new ExchangeSettlementRunResult(false, 2, 1, 1, 0, 0, 0, 1, false));
        assertThat(status(broken)).isEqualTo("APPROVED");
        assertThat(status(ok)).isEqualTo("COMPLETED");

        fixed = fixed.plus(Duration.ofHours(1));
        ExchangeSettlementRunResult second = settlementUseCase.run();

        assertThat(second).isEqualTo(new ExchangeSettlementRunResult(false, 1, 0, 0, 0, 0, 0, 1, false));
        assertThat(status(broken)).isEqualTo("APPROVED");
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("정산일에 서버가 멈췄어도 이후 실행에서 지난 회차의 승인 건을 송금하고, 정산 예정 시각을 바꾸지 않는다")
    void overdueApprovedIsSettledLater() {
        influencer(20_000L);
        Long exchangeId = approvedExchange(10_000L);
        Instant round = scheduledAt(exchangeId);
        plan(MockPayoutScenario.SUCCESS);
        fixed = round.plus(Duration.ofDays(3)).plus(Duration.ofHours(15));

        ExchangeSettlementRunResult result = settlementUseCase.run();

        assertThat(result.completed()).isEqualTo(1);
        assertThat(scheduledAt(exchangeId)).isEqualTo(round);
        assertThat(status(exchangeId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("정산일 이후 승인한 환전은 다음 회차로 옮겨져, 원래 정산일 실행에서는 보내지 않고 다음 정산일에 보낸다")
    void lateApprovalWaitsForNextRound() {
        influencer(20_000L);
        Long exchangeId = request(10_000L);
        Instant provisional = scheduledAt(exchangeId);
        fixed = provisional.plus(Duration.ofHours(9)); // 정산일 09:00 KST에 승인 → 다음 회차
        reviewUseCase.approve(ADMIN, exchangeId);
        Instant nextRound = scheduledAt(exchangeId);
        assertThat(nextRound).isEqualTo(ExchangeSettlementRound.firstRoundAfter(fixed)).isAfter(provisional);
        plan(MockPayoutScenario.SUCCESS);

        fixed = provisional.plus(Duration.ofHours(10).plusMinutes(5)); // 원래 정산일 10:05
        assertThat(settlementUseCase.run().targets()).isZero();
        fixed = nextRound.minus(1, ChronoUnit.MICROS);
        assertThat(settlementUseCase.run().targets()).isZero();
        assertThat(status(exchangeId)).isEqualTo("APPROVED");

        fixed = nextRound.plus(Duration.ofHours(10).plusMinutes(5)); // 다음 정산일 10:05
        ExchangeSettlementRunResult result = settlementUseCase.run();

        assertThat(result.completed()).isEqualTo(1);
        assertThat(status(exchangeId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("응답이 끊긴(TIMEOUT) 송금은 결과 대기로 남고, 결과 확인이 같은 지급 요청 ID로 조회해 성공 반영한다(새 ID 없음, 송금 1번)")
    void timeoutRecoveredWithSameRequestId() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(10_000L);
        String id = plan(MockPayoutScenario.TIMEOUT).getFirst();
        Instant start = scheduledAt(exchangeId).plus(Duration.ofHours(10).plusMinutes(5));
        fixed = start;

        ExchangeSettlementRunResult result = settlementUseCase.run();

        assertThat(result).isEqualTo(new ExchangeSettlementRunResult(false, 1, 1, 0, 0, 1, 0, 0, false));
        assertThat(status(exchangeId)).isEqualTo("UNKNOWN");
        // 다음 정산 실행은 결과 대기 건을 다시 보내지 않는다(APPROVED가 아니다)
        fixed = start.plus(Duration.ofHours(1));
        assertThat(settlementUseCase.run().targets()).isZero();

        ExchangePayoutRecoveryResult recovered = payoutUseCase.recoverStale();

        assertThat(recovered.completed()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT payout_request_id FROM billing.exchange_payout", String.class))
                .containsExactly(id);
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);
        assertThat(captureCount()).isEqualTo(1);
        assertWallet(walletId, 10_000L, 0L);
    }

    @Test
    @DisplayName("최초 송금 요청 후 24시간 안에 결과를 모르면 결과 확인이 운영자 확인으로 넘기고 HP를 반환하지 않는다")
    void recoveryStopsAfter24Hours() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(10_000L);
        String id = plan(MockPayoutScenario.SUCCESS).getFirst();
        doReturn(new com.hapbang.billing.out.PayoutTransferResult.Unknown(id, "NO_RESPONSE")).when(client)
                .send(any());
        doReturn(new com.hapbang.billing.out.PayoutTransferResult.Unknown(id, "NO_RESPONSE")).when(client)
                .find(any());
        Instant start = scheduledAt(exchangeId).plus(Duration.ofHours(10).plusMinutes(5));
        fixed = start;
        settlementUseCase.run();

        fixed = start.plus(STALE);
        assertThat(payoutUseCase.recoverStale().inProgress()).isEqualTo(1);
        fixed = start.plus(Duration.ofHours(24)).plusSeconds(60);
        ExchangePayoutRecoveryResult expired = payoutUseCase.recoverStale();

        assertThat(expired.manualReview()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing.exchange_payout", String.class))
                .isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(holdStatus(exchangeId)).isEqualTo("HELD");
        assertWallet(walletId, 10_000L, 10_000L);
        fixed = fixed.plus(Duration.ofHours(1));
        assertThat(settlementUseCase.run().targets()).isZero();
        assertThat(count("billing.exchange_payout")).isEqualTo(1);
    }

    @Test
    @DisplayName("정산 실행과 결과 확인이 동시에 여러 번 돌아도 환전마다 송금 1번·확정 차감 1번이다")
    void concurrentSettlementAndRecovery() throws Exception {
        Long walletId = influencer(40_000L);
        List<Long> exchanges = List.of(approvedExchange(10_000L), approvedExchange(10_000L), approvedExchange(10_000L));
        plan(MockPayoutScenario.DELAY, MockPayoutScenario.DELAY, MockPayoutScenario.DELAY);
        fixed = scheduledAt(exchanges.getFirst()).plus(Duration.ofHours(10).plusMinutes(5));

        runConcurrently(List.of(settlementUseCase::run, settlementUseCase::run, payoutUseCase::recoverStale,
                settlementUseCase::run, payoutUseCase::recoverStale));
        fixed = fixed.plus(STALE);
        payoutUseCase.recoverStale();

        assertThat(exchanges).allSatisfy(id -> assertThat(status(id)).isEqualTo("COMPLETED"));
        assertThat(count("billing.exchange_payout")).isEqualTo(3);
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(3);
        assertThat(captureCount()).isEqualTo(3);
        assertWallet(walletId, 10_000L, 0L);
    }

    @Test
    @DisplayName("송금 Client를 쓸 수 없는 환경(Mock 꺼짐)이면 정산 실행·결과 확인 모두 송금 없이 건너뛴다")
    void skipsWhenClientUnavailable() {
        influencer(20_000L);
        Long exchangeId = approvedExchange(10_000L);
        fixed = scheduledAt(exchangeId).plus(Duration.ofHours(10));
        doReturn(false).when(client).isAvailable();

        assertThat(settlementUseCase.run().skipped()).isTrue();
        assertThat(payoutUseCase.recoverStale()).isEqualTo(ExchangePayoutRecoveryResult.EMPTY);
        verify(client, never()).send(any());
        assertThat(status(exchangeId)).isEqualTo("APPROVED");
        assertThat(count("billing.exchange_payout")).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private Long influencer(long revenueHp) {
        Long walletId = walletRepository.save(Wallet.create(INFLUENCER)).getId();
        ledgerRecordUseCase.record(new LedgerOperation("SEED:" + sequence.incrementAndGet(),
                LedgerTransactionType.PAYOUT, BillingReferenceType.SETTLEMENT, (long) sequence.get(), null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, revenueHp))));
        billingUserSyncUseCase.apply(new UserDto(INFLUENCER, "신청자", Role.INFLUENCER, UserStatus.ACTIVE, 0L));
        return walletId;
    }

    private Long request(long amountHp) {
        return exchangeRequestUseCase.create(INFLUENCER, "key-" + sequence.incrementAndGet(),
                new ExchangeCommand(amountHp, "004", ACCOUNT, "홍길동"));
    }

    // 신청 직후(정산일 전) 승인해 신청 때 잠정 회차가 그대로 확정된 환전
    private Long approvedExchange(long amountHp) {
        Long exchangeId = request(amountHp);
        reviewUseCase.approve(ADMIN, exchangeId);
        return exchangeId;
    }

    // 다음 송금 시도들의 지급 요청 ID를 정하고 각 ID의 Mock 시나리오를 미리 정한다(시도 순서대로)
    private List<String> plan(MockPayoutScenario... planned) {
        List<String> ids = Arrays.stream(planned).map(scenario -> {
            String id = UUID.randomUUID().toString();
            scenarios.set(id, scenario);
            return id;
        }).toList();
        doReturn(ids.getFirst(), ids.subList(1, ids.size()).toArray()).when(requestIds).next();
        return ids;
    }

    private Instant scheduledAt(Long exchangeId) {
        return jdbcTemplate.queryForObject("SELECT payout_scheduled_at FROM billing.exchange WHERE id = ?",
                OffsetDateTime.class, exchangeId).toInstant();
    }

    private String status(Long exchangeId) {
        return jdbcTemplate.queryForObject("SELECT status FROM billing.exchange WHERE id = ?", String.class,
                exchangeId);
    }

    private String holdStatus(Long exchangeId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM billing.point_hold WHERE reference_type = 'EXCHANGE' AND reference_id = ?",
                String.class, exchangeId);
    }

    private long captureCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_hold_detail WHERE action_type = 'CAPTURE'", Long.class);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void assertWallet(Long walletId, long revenue, long heldRevenue) {
        assertThat(walletRepository.findById(walletId).orElseThrow())
                .extracting(Wallet::getRevenueBalanceHp, Wallet::getHeldRevenueHp).containsExactly(revenue, heldRevenue);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }
}
