package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.out.MockPayoutHttpClient;
import com.hapbang.billing.out.MockPayoutScenario;
import com.hapbang.billing.out.PayoutTransferRequest;
import com.hapbang.billing.out.PayoutTransferResult;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;

// 환전 가상 송금 실행·결과 반영·운영자 처리(#304)를 실제 PostgreSQL과 실제 HTTP(랜덤 포트 서버의 Mock 은행)로 검증한다.
// 관리자 API는 같은 서버 컨텍스트에서 MockMvc로 검증한다(권한·RsData·오류 코드).
// 환전 신청(#285) → 관리자 승인(#294) → ExchangePayoutUseCase 송금(#288 Client)까지 실제 코드로 진행한다.
// - 시간: ExchangePayoutClock만 timeShift만큼 앞당긴다(정산 예정 시각 도래·결과 확인 대기 시간). 신청 시각·잠정 정산일은 실제 시각이다
// - Mock 시나리오: 지급 요청 ID 생성기(ExchangePayoutRequestIds)가 테스트가 정한 UUID를 돌려주게 해 시도마다 시나리오를 미리 정한다
// - 응답 시간 제한 1초, DELAY 0.3초, TIMEOUT 2.5초(Mock 처리 후 응답만 늦음 = 응답 유실)
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
                "hapbang.billing.mock-payout.enabled=true",
                "hapbang.billing.mock-payout.connect-timeout=1s",
                "hapbang.billing.mock-payout.read-timeout=1s",
                "hapbang.billing.mock-payout.delay=300ms",
                "hapbang.billing.mock-payout.timeout-delay=2500ms",
                "hapbang.billing.exchange.payout.recovery-after=PT1M",
                // 이 클래스만 쓰는 컨텍스트(실제 서버)라 연결 풀을 작게 두고 끝나면 닫는다(다른 테스트 컨텍스트와 함께 DB 최대 연결 수를 넘지 않게)
                "spring.datasource.hikari.maximum-pool-size=6",
                "spring.datasource.hikari.minimum-idle=1"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureMockMvc
class ExchangePayoutIntegrationTest {

    private static final long INFLUENCER = 2L;
    private static final long ADMIN = 900L;
    private static final long OTHER_ADMIN = 901L;
    private static final String PATH = "/api/v1/settlements/admin/exchanges";
    private static final String ACCOUNT = "12345678901234";
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    // 잠정 정산 예정일(다음 10일, 주말이면 다음 월요일)이 확실히 지난 시각
    private static final Duration AFTER_SCHEDULE = Duration.ofDays(50);
    // 결과 확인 대기 시간(recovery-after 1분)이 지난 시각
    private static final Duration STALE = Duration.ofMinutes(2);

    @DynamicPropertySource
    static void cipherProperties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.billing.payment-key.encryption-key", () -> ChargeFixture.ENCRYPTION_KEY);
        registry.add("hapbang.billing.payment-key.hmac-key", () -> ChargeFixture.HMAC_KEY);
    }

    @Autowired
    ExchangePayoutUseCase payoutUseCase;

    @Autowired
    ExchangePayoutProcessing payoutProcessing;

    @Autowired
    ExchangeRequestUseCase exchangeRequestUseCase;

    @Autowired
    ExchangeReviewUseCase reviewUseCase;

    @Autowired
    ExchangeProcessing exchangeProcessing;

    @Autowired
    BillingUserSyncUseCase billingUserSyncUseCase;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    MockPayoutScenarios scenarios;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    @MockitoSpyBean
    ExchangePayoutClock clock;

    @MockitoSpyBean
    ExchangePayoutRequestIds requestIds;

    @MockitoSpyBean
    MockPayoutHttpClient client;

    @MockitoSpyBean
    InternalHoldFacade internalHoldFacade;

    private volatile Duration timeShift = Duration.ZERO;
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        timeShift = Duration.ZERO;
        doAnswer(invocation -> Instant.now().plus(timeShift).truncatedTo(ChronoUnit.MICROS)).when(clock).now();
    }

    // ---------------------------------------------------------------------------------------------
    // 성공·정산 예정일·대상 상태
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("성공: 승인 건을 정산 예정 시각 뒤에 송금하면 시도 COMPLETED·환전 COMPLETED, 보류 전액 확정 차감(EXCHANGE 원장 1줄), "
            + "Mock 기록 1건이고 계좌번호는 로그에 없다")
    void success(CapturedOutput output) {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.SUCCESS).getFirst();
        timeShift = AFTER_SCHEDULE;

        ExchangePayoutResult result = payoutUseCase.execute(exchangeId);

        assertThat(result.exchangeStatus()).isEqualTo(ExchangeStatus.COMPLETED);
        assertThat(result.payoutStatus()).isEqualTo(ExchangePayoutStatus.COMPLETED);
        assertThat(result.action()).isEqualTo(ExchangePayoutAction.SENT);
        assertThat(result.attemptNo()).isEqualTo(1);
        assertThat(result.payoutRequestId()).isEqualTo(id);
        assertThat(result.transactionId()).startsWith("MOCKTX-");
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "COMPLETED").containsEntry("approved_by", ADMIN)
                .containsEntry("failed_at", null);
        assertThat(exchangeRow(exchangeId).get("completed_at")).isNotNull();
        assertThat(payoutRows(exchangeId)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "COMPLETED").containsEntry("amount_krw", 13_500L)
                .containsEntry("payout_request_id", id).containsEntry("attempt_no", 1)
                .containsEntry("payout_transaction_id", result.transactionId()));
        assertThat(transfer(id)).containsEntry("status", "SUCCEEDED").containsEntry("amount_krw", 13_500L)
                .containsEntry("exchange_id", exchangeId);
        assertThat(holdOf(exchangeId)).containsEntry("status", "CAPTURED").containsEntry("held_revenue_hp", 0L);
        assertThat(captureKeys(exchangeId)).containsExactly(
                "CAPTURE:" + holdOf(exchangeId).get("id") + ":EXCHANGE-PAYOUT-" + exchangeId);
        assertThat(ledgerOf(exchangeId)).containsExactly(
                "REVENUE AVAILABLE -15000 HOLD", "REVENUE HELD 15000 HOLD", "REVENUE HELD -15000 EXCHANGE");
        assertWallet(walletId, 5_000L, 0L);
        assertThat(output.getAll()).doesNotContain(ACCOUNT);
    }

    @Test
    @DisplayName("송금이 끝난 환전을 다시 실행해도 은행을 부르지 않고(NONE) 시도·Mock 기록·확정 차감·원장이 늘지 않는다")
    void completedIsIdempotent() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        plan(MockPayoutScenario.SUCCESS);
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);
        long ledgerBefore = count("billing.point_ledger");

        ExchangePayoutResult again = payoutUseCase.execute(exchangeId);
        assertBillingError(() -> payoutUseCase.retryFailed(ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_RETRYABLE);
        assertBillingError(() -> payoutProcessing.failFinally(ADMIN, exchangeId, "사유"),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
        timeShift = AFTER_SCHEDULE.plus(STALE);
        ExchangePayoutRecoveryResult recovered = payoutUseCase.recoverStale();

        assertThat(again.action()).isEqualTo(ExchangePayoutAction.NONE);
        assertThat(again.exchangeStatus()).isEqualTo(ExchangeStatus.COMPLETED);
        assertThat(recovered.targets()).isZero();
        assertThat(count("billing.exchange_payout")).isEqualTo(1);
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);
        assertThat(count("billing.point_ledger")).isEqualTo(ledgerBefore);
        assertThat(captureKeys(exchangeId)).hasSize(1);
        assertWallet(walletId, 5_000L, 0L);
    }

    @Test
    @DisplayName("정산 예정 시각 전에는 승인 건도 송금하지 않는다(EXCHANGE_PAYOUT_NOT_DUE, 시도·Mock 기록 없음, APPROVED 유지)")
    void notDue() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        Instant scheduledAt = ((java.sql.Timestamp) exchangeRow(exchangeId).get("payout_scheduled_at")).toInstant();
        // 정산 예정 시각 1초 전
        timeShift = Duration.between(Instant.now(), scheduledAt).minusSeconds(1);

        assertBillingError(() -> payoutUseCase.execute(exchangeId), BillingErrorCode.EXCHANGE_PAYOUT_NOT_DUE);

        assertThat(exchangeRow(exchangeId)).containsEntry("status", "APPROVED");
        assertThat(count("billing.exchange_payout")).isZero();
        assertThat(count("billing.mock_payout_transfer")).isZero();
        assertWallet(walletId, 5_000L, 15_000L);

        // 정산 예정 시각 정각부터 송금한다
        plan(MockPayoutScenario.SUCCESS);
        timeShift = Duration.between(Instant.now(), scheduledAt).plusSeconds(1);
        assertThat(payoutUseCase.execute(exchangeId).exchangeStatus()).isEqualTo(ExchangeStatus.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "REJECTED", "CANCELED"})
    @DisplayName("승인되지 않은(신청·거절·취소) 환전은 송금하지 않는다(EXCHANGE_NOT_PAYABLE, 시도·Mock 기록 없음)")
    void notApproved(String status) {
        Long walletId = influencer(20_000L);
        Long exchangeId = request(15_000L);
        switch (status) {
            case "REJECTED" -> reviewUseCase.reject(ADMIN, exchangeId, "계좌 불일치");
            case "CANCELED" -> exchangeProcessing.cancel(INFLUENCER, exchangeId);
            default -> {
            }
        }
        timeShift = AFTER_SCHEDULE;

        assertBillingError(() -> payoutUseCase.execute(exchangeId), BillingErrorCode.EXCHANGE_NOT_PAYABLE);
        assertBillingError(() -> payoutUseCase.retryFailed(ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_RETRYABLE);

        assertThat(exchangeRow(exchangeId)).containsEntry("status", status);
        assertThat(count("billing.exchange_payout")).isZero();
        assertThat(count("billing.mock_payout_transfer")).isZero();
        assertThat(captureKeys(exchangeId)).isEmpty();
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("없는 환전은 EXCHANGE_NOT_FOUND")
    void notFound() {
        assertBillingError(() -> payoutUseCase.execute(999_999L), BillingErrorCode.EXCHANGE_NOT_FOUND);
    }

    @Test
    @DisplayName("송금을 쓸 수 없는 환경이면 EXCHANGE_PAYOUT_UNAVAILABLE(시도 없음), 결과 확인은 아무것도 하지 않는다")
    void unavailable() {
        influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        timeShift = AFTER_SCHEDULE;
        doReturn(false).when(client).isAvailable();

        assertBillingError(() -> payoutUseCase.execute(exchangeId), BillingErrorCode.EXCHANGE_PAYOUT_UNAVAILABLE);
        assertThat(payoutUseCase.recoverStale()).isEqualTo(ExchangePayoutRecoveryResult.EMPTY);

        assertThat(exchangeRow(exchangeId)).containsEntry("status", "APPROVED");
        assertThat(count("billing.exchange_payout")).isZero();
    }

    @Test
    @DisplayName("보류가 신청 금액 그대로가 아니면(일부 반환됨) 송금하지 않는다(EXCHANGE_STATE_INCONSISTENT, 시도 없음)")
    void holdNotIntact() {
        influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        internalHoldFacade.release(InternalReleaseCommand.partial(INFLUENCER, BillingReferenceType.EXCHANGE, exchangeId,
                "TEST-PARTIAL", 1_000L));
        timeShift = AFTER_SCHEDULE;

        assertBillingError(() -> payoutUseCase.execute(exchangeId), BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);

        assertThat(exchangeRow(exchangeId)).containsEntry("status", "APPROVED");
        assertThat(count("billing.exchange_payout")).isZero();
        assertThat(count("billing.mock_payout_transfer")).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 확정 실패·재송금
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("확정 실패: 시도·환전 FAILED, 보류는 반환하지 않고 유지한다. 실행·결과 확인으로 자동 재송금하지 않고, 관리자 재송금만 새 "
            + "지급 요청 ID의 2번째 시도(보낸 관리자 기록)로 보내 성공하면 확정 차감은 한 번이다")
    void failedThenRetry() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        List<String> ids = plan(MockPayoutScenario.FAILED, MockPayoutScenario.SUCCESS);
        timeShift = AFTER_SCHEDULE;

        ExchangePayoutResult failed = payoutUseCase.execute(exchangeId);

        assertThat(failed.exchangeStatus()).isEqualTo(ExchangeStatus.FAILED);
        assertThat(failed.payoutStatus()).isEqualTo(ExchangePayoutStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("MOCK_TRANSFER_REJECTED");
        assertThat(exchangeRow(exchangeId)).containsEntry("failure_reason", "MOCK_PAYOUT_FAILED:MOCK_TRANSFER_REJECTED");
        assertThat(exchangeRow(exchangeId).get("failed_at")).isNotNull();
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD").containsEntry("held_revenue_hp", 15_000L);
        assertThat(releaseKeys(exchangeId)).isEmpty();
        assertWallet(walletId, 5_000L, 15_000L);

        // 실패 건은 execute·결과 확인으로 다시 보내지 않는다
        assertThat(payoutUseCase.execute(exchangeId).action()).isEqualTo(ExchangePayoutAction.NONE);
        timeShift = AFTER_SCHEDULE.plus(STALE);
        assertThat(payoutUseCase.recoverStale().targets()).isZero();
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);

        ExchangePayoutResult retried = payoutUseCase.retryFailed(ADMIN, exchangeId);

        assertThat(retried.action()).isEqualTo(ExchangePayoutAction.SENT);
        assertThat(retried.exchangeStatus()).isEqualTo(ExchangeStatus.COMPLETED);
        assertThat(retried.attemptNo()).isEqualTo(2);
        assertThat(retried.payoutRequestId()).isEqualTo(ids.get(1)).isNotEqualTo(ids.get(0));
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "COMPLETED").containsEntry("failed_at", null)
                .containsEntry("failure_reason", null);
        assertThat(payoutRows(exchangeId)).extracting(row -> row.get("attempt_no") + ":" + row.get("status")
                + ":" + row.get("requested_by")).containsExactly("1:FAILED:null", "2:COMPLETED:" + ADMIN);
        assertThat(transfer(ids.get(0))).containsEntry("status", "FAILED");
        assertThat(transfer(ids.get(1))).containsEntry("status", "SUCCEEDED");
        assertThat(captureKeys(exchangeId)).hasSize(1);
        assertThat(releaseKeys(exchangeId)).isEmpty();
        assertWallet(walletId, 5_000L, 0L);
    }

    // ---------------------------------------------------------------------------------------------
    // 결과 불명·결과 확인
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("시간 초과(Mock은 성공 처리, 응답 유실): UNKNOWN·보류 유지 → 바로 다시 실행해도 보내지 않고 → 대기 시간 뒤 결과 확인이 "
            + "같은 ID로 조회해 성공 반영(재송금 없음)")
    void timeoutThenRecover() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.TIMEOUT).getFirst();
        timeShift = AFTER_SCHEDULE;

        ExchangePayoutResult unknown = payoutUseCase.execute(exchangeId);

        assertThat(unknown.exchangeStatus()).isEqualTo(ExchangeStatus.UNKNOWN);
        assertThat(unknown.payoutStatus()).isEqualTo(ExchangePayoutStatus.UNKNOWN);
        assertThat(unknown.failureReason()).isEqualTo("NO_RESPONSE");
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD").containsEntry("held_revenue_hp", 15_000L);
        assertWallet(walletId, 5_000L, 15_000L);

        // 방금 보낸 시도는 다른 실행이 처리 중일 수 있으므로 바로 다시 확인하지 않는다
        assertThat(payoutUseCase.execute(exchangeId).action()).isEqualTo(ExchangePayoutAction.NONE);
        assertThat(payoutUseCase.recoverStale().targets()).isZero();

        timeShift = AFTER_SCHEDULE.plus(STALE);
        ExchangePayoutRecoveryResult recovered = payoutUseCase.recoverStale();

        assertThat(recovered).isEqualTo(new ExchangePayoutRecoveryResult(1, 1, 0, 0, 0, 0));
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "COMPLETED");
        assertThat(payoutRows(exchangeId)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "COMPLETED").containsEntry("payout_request_id", id));
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);
        assertThat(captureKeys(exchangeId)).hasSize(1);
        assertWallet(walletId, 5_000L, 0L);
    }

    @Test
    @DisplayName("처리 전 5xx(Mock 기록 없음): UNKNOWN → 결과 확인에서 조회 NotFound → 같은 ID·같은 내용으로 재전송(RESENT)해 성공")
    void serverErrorThenResend() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.SERVER_ERROR).getFirst();
        timeShift = AFTER_SCHEDULE;

        ExchangePayoutResult unknown = payoutUseCase.execute(exchangeId);

        assertThat(unknown.exchangeStatus()).isEqualTo(ExchangeStatus.UNKNOWN);
        assertThat(unknown.failureReason()).isEqualTo("500:MOCK_PAYOUT_SERVER_ERROR");
        assertThat(count("billing.mock_payout_transfer")).isZero();

        timeShift = AFTER_SCHEDULE.plus(STALE);
        ExchangePayoutResult resent = payoutUseCase.execute(exchangeId);

        assertThat(resent.action()).isEqualTo(ExchangePayoutAction.RESENT);
        assertThat(resent.exchangeStatus()).isEqualTo(ExchangeStatus.COMPLETED);
        assertThat(resent.payoutRequestId()).isEqualTo(id);
        assertThat(count("billing.exchange_payout")).isEqualTo(1);
        assertThat(transfer(id)).containsEntry("status", "SUCCEEDED");
        assertWallet(walletId, 5_000L, 0L);
    }

    @Test
    @DisplayName("Mock 은행이 아직 처리 중(PROCESSING)이면 시도·환전 PROCESSING, 보류 유지")
    void bankProcessing() {
        influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.SUCCESS).getFirst();
        doReturn(new PayoutTransferResult.Processing(id)).when(client).send(any());
        timeShift = AFTER_SCHEDULE;

        ExchangePayoutResult result = payoutUseCase.execute(exchangeId);

        assertThat(result.exchangeStatus()).isEqualTo(ExchangeStatus.PROCESSING);
        assertThat(result.payoutStatus()).isEqualTo(ExchangePayoutStatus.PROCESSING);
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD");

        // 결과 확인: 은행에 기록이 없으므로 같은 ID로 재전송(실제 Mock)해 성공
        doCallRealMethod().when(client).send(any());
        timeShift = AFTER_SCHEDULE.plus(STALE);
        assertThat(payoutUseCase.recoverStale().completed()).isEqualTo(1);
        assertThat(transfer(id)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    @DisplayName("Mock 송금 성공 후 Billing 반영(확정 차감)이 실패하면 시도는 PROCESSING으로 남고, 결과 확인이 조회로 성공을 반영한다"
            + "(재송금 없음, 확정 차감 1회)")
    void applyFailsAfterBankSuccess() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.SUCCESS).getFirst();
        doThrow(new IllegalStateException("DB 반영 실패 시뮬레이션")).doCallRealMethod()
                .when(internalHoldFacade).capture(any());
        timeShift = AFTER_SCHEDULE;

        ExchangePayoutResult result = payoutUseCase.execute(exchangeId);

        assertThat(result.exchangeStatus()).isEqualTo(ExchangeStatus.PROCESSING);
        assertThat(result.payoutStatus()).isEqualTo(ExchangePayoutStatus.PROCESSING);
        assertThat(transfer(id)).containsEntry("status", "SUCCEEDED");
        assertThat(captureKeys(exchangeId)).isEmpty();
        assertWallet(walletId, 5_000L, 15_000L);

        timeShift = AFTER_SCHEDULE.plus(STALE);
        ExchangePayoutResult recovered = payoutUseCase.execute(exchangeId);

        assertThat(recovered.action()).isEqualTo(ExchangePayoutAction.CHECKED);
        assertThat(recovered.exchangeStatus()).isEqualTo(ExchangeStatus.COMPLETED);
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);
        assertThat(captureKeys(exchangeId)).hasSize(1);
        assertWallet(walletId, 5_000L, 0L);
    }

    @Test
    @DisplayName("시도 저장·커밋 직후 서버가 멈춰 보내지 못한 경우: 재시작 뒤 결과 확인이 조회(NotFound) 후 같은 ID로 보내 성공한다")
    void crashBeforeSend() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.SUCCESS).getFirst();
        timeShift = AFTER_SCHEDULE;
        // 트랜잭션 1만 실행(시도 PROCESSING 커밋)하고 HTTP는 보내지 않는다
        assertThat(payoutProcessing.start(exchangeId, Instant.now()))
                .isInstanceOf(ExchangePayoutProcessing.Step.Send.class);
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "PROCESSING");
        assertThat(count("billing.mock_payout_transfer")).isZero();

        timeShift = AFTER_SCHEDULE.plus(STALE);
        ExchangePayoutRecoveryResult recovered = payoutUseCase.recoverStale();

        assertThat(recovered.completed()).isEqualTo(1);
        assertThat(transfer(id)).containsEntry("status", "SUCCEEDED");
        assertThat(count("billing.exchange_payout")).isEqualTo(1);
        assertWallet(walletId, 5_000L, 0L);
    }

    @Test
    @DisplayName("조회 결과 확정 실패면 FAILED(보류 유지)로 끝나고 결과 확인 대상에서 빠진다")
    void recoverToFailed() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.FAILED).getFirst();
        doReturn(new PayoutTransferResult.Unknown(id, "NO_RESPONSE")).doCallRealMethod().when(client).send(any());
        timeShift = AFTER_SCHEDULE;
        // 은행 기록을 먼저 만든다(첫 응답만 유실된 상황)
        assertThat(payoutUseCase.execute(exchangeId).exchangeStatus()).isEqualTo(ExchangeStatus.UNKNOWN);
        assertThat(count("billing.mock_payout_transfer")).isZero();

        timeShift = AFTER_SCHEDULE.plus(STALE);
        ExchangePayoutResult resent = payoutUseCase.execute(exchangeId);
        assertThat(resent.exchangeStatus()).isEqualTo(ExchangeStatus.FAILED);
        timeShift = AFTER_SCHEDULE.plus(STALE).plus(STALE);

        assertThat(payoutUseCase.recoverStale().targets()).isZero();
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD").containsEntry("held_revenue_hp", 15_000L);
        assertWallet(walletId, 5_000L, 15_000L);
    }

    // ---------------------------------------------------------------------------------------------
    // 운영자 확인
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"CONFLICT", "REJECTED", "AMOUNT_MISMATCH", "NO_TRANSACTION"})
    @DisplayName("409·그 밖의 4xx 거절·성공 응답 불일치: 시도 MANUAL_REVIEW_REQUIRED, 환전 UNKNOWN, 보류 유지·확정 차감 없음, "
            + "결과 확인·재실행 대상에서 빠진다")
    void manualReview(String kind) {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.SUCCESS).getFirst();
        PayoutTransferResult response = switch (kind) {
            case "CONFLICT" -> new PayoutTransferResult.Conflict(id, "MOCK_PAYOUT_IDEMPOTENCY_CONFLICT");
            case "REJECTED" -> new PayoutTransferResult.Rejected(id, 400, "MOCK_PAYOUT_INVALID_REQUEST");
            case "AMOUNT_MISMATCH" -> new PayoutTransferResult.Succeeded(id, "MOCKTX-X", 15_000L, Instant.now());
            default -> new PayoutTransferResult.Succeeded(id, " ", 13_500L, Instant.now());
        };
        doReturn(response).when(client).send(any());
        timeShift = AFTER_SCHEDULE;

        ExchangePayoutResult result = payoutUseCase.execute(exchangeId);

        assertThat(result.exchangeStatus()).isEqualTo(ExchangeStatus.UNKNOWN);
        assertThat(result.payoutStatus()).isEqualTo(ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(result.failureReason()).isNotBlank();
        assertThat(captureKeys(exchangeId)).isEmpty();
        assertWallet(walletId, 5_000L, 15_000L);

        timeShift = AFTER_SCHEDULE.plus(STALE);
        assertThat(payoutUseCase.recoverStale().targets()).isZero();
        assertThat(payoutUseCase.execute(exchangeId).action()).isEqualTo(ExchangePayoutAction.NONE);
        assertBillingError(() -> payoutUseCase.retryFailed(ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_RETRYABLE);
        assertBillingError(() -> payoutProcessing.failFinally(ADMIN, exchangeId, "사유"),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
        assertThat(count("billing.exchange_payout")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // 최종 실패(관리자)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("최종 실패 확정: FAILED 환전을 FINAL_FAILED로 종료하고 보류한 수익 HP 전부를 AVAILABLE로 반환한다(RELEASE 원장 2줄, "
            + "반환 요청 ID EXCHANGE-PAYOUT-FAIL-{id}). 같은 요청 반복은 다시 반환하지 않고, 이후 송금·재송금·결과 확인은 없다")
    void failFinally() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        plan(MockPayoutScenario.FAILED);
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);
        assertWallet(walletId, 5_000L, 15_000L);

        ExchangePayoutResult result = payoutUseCase.failFinally(ADMIN, exchangeId, "  계좌 오류로 송금 불가  ");

        assertThat(result.exchangeStatus()).isEqualTo(ExchangeStatus.FINAL_FAILED);
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "FINAL_FAILED")
                .containsEntry("final_failed_by", ADMIN).containsEntry("final_failure_reason", "계좌 오류로 송금 불가")
                .containsEntry("failure_reason", "MOCK_PAYOUT_FAILED:MOCK_TRANSFER_REJECTED");
        assertThat(exchangeRow(exchangeId).get("final_failed_at")).isNotNull();
        assertThat(holdOf(exchangeId)).containsEntry("status", "RELEASED").containsEntry("held_revenue_hp", 0L);
        assertThat(releaseKeys(exchangeId)).containsExactly(
                "RELEASE:" + holdOf(exchangeId).get("id") + ":EXCHANGE-PAYOUT-FAIL-" + exchangeId);
        assertThat(ledgerOf(exchangeId)).containsExactly("REVENUE AVAILABLE -15000 HOLD", "REVENUE HELD 15000 HOLD",
                "REVENUE HELD -15000 RELEASE", "REVENUE AVAILABLE 15000 RELEASE");
        assertThat(captureKeys(exchangeId)).isEmpty();
        assertWallet(walletId, 20_000L, 0L);
        long ledgerAfter = count("billing.point_ledger");

        // 같은 관리자·같은 사유 재요청은 처음 결과(다시 반환하지 않음), 그 밖은 409
        assertThat(payoutUseCase.failFinally(ADMIN, exchangeId, "계좌 오류로 송금 불가").exchangeStatus())
                .isEqualTo(ExchangeStatus.FINAL_FAILED);
        assertBillingError(() -> payoutUseCase.failFinally(ADMIN, exchangeId, "다른 사유"),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
        assertBillingError(() -> payoutUseCase.failFinally(OTHER_ADMIN, exchangeId, "계좌 오류로 송금 불가"),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
        // 최종 실패 뒤에는 송금·재송금·결과 재확인을 하지 않는다
        assertThat(payoutUseCase.execute(exchangeId).action()).isEqualTo(ExchangePayoutAction.NONE);
        assertBillingError(() -> payoutUseCase.retryFailed(ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_RETRYABLE);
        assertBillingError(() -> payoutUseCase.recheck(ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_CHECKABLE);
        timeShift = AFTER_SCHEDULE.plus(STALE);
        assertThat(payoutUseCase.recoverStale().targets()).isZero();
        assertThat(releaseKeys(exchangeId)).hasSize(1);
        assertThat(count("billing.point_ledger")).isEqualTo(ledgerAfter);
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);
        assertConsistent(walletId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("최종 실패 사유가 비었으면 EXCHANGE_FINAL_FAILURE_REASON_INVALID이고 아무것도 바뀌지 않는다")
    void failFinallyReasonInvalid(String reason) {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        plan(MockPayoutScenario.FAILED);
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);

        assertBillingError(() -> payoutUseCase.failFinally(ADMIN, exchangeId, reason),
                BillingErrorCode.EXCHANGE_FINAL_FAILURE_REASON_INVALID);

        assertThat(exchangeRow(exchangeId)).containsEntry("status", "FAILED");
        assertWallet(walletId, 5_000L, 15_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "PROCESSING", "MANUAL_REVIEW", "APPROVED"})
    @DisplayName("성공 가능성이 남은 건(결과 불명·처리 중·운영자 확인)과 송금 전 건은 최종 실패로 종료할 수 없다(보류 유지·반환 없음)")
    void failFinallyNotAllowedWhileUncertain(String state) {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.SUCCESS).getFirst();
        switch (state) {
            case "UNKNOWN" -> doReturn(new PayoutTransferResult.Unknown(id, "NO_RESPONSE")).when(client).send(any());
            case "PROCESSING" -> doReturn(new PayoutTransferResult.Processing(id)).when(client).send(any());
            case "MANUAL_REVIEW" -> doReturn(new PayoutTransferResult.Rejected(id, 400, "X")).when(client).send(any());
            default -> {
            }
        }
        timeShift = AFTER_SCHEDULE;
        if (!"APPROVED".equals(state)) {
            payoutUseCase.execute(exchangeId);
        }

        assertBillingError(() -> payoutUseCase.failFinally(ADMIN, exchangeId, "사유"),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
        assertBillingError(() -> payoutUseCase.retryFailed(ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_RETRYABLE);

        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD").containsEntry("held_revenue_hp", 15_000L);
        assertThat(releaseKeys(exchangeId)).isEmpty();
        assertWallet(walletId, 5_000L, 15_000L);
    }

    // ---------------------------------------------------------------------------------------------
    // 자동 결과 확인 기한(24시간)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("24시간 안에는 1분 간격으로 같은 ID를 확인하며 횟수를 센다. 최초 송금 요청 후 24시간이 지나면 은행을 부르지 않고 "
            + "MANUAL_REVIEW_REQUIRED(AUTO_CHECK_EXPIRED)로 넘기고 HP를 반환하지 않는다. 이후 자동 확인·재송금 대상이 아니다")
    void autoCheckExpires() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.SUCCESS).getFirst();
        doReturn(new PayoutTransferResult.Unknown(id, "NO_RESPONSE")).when(client).send(any());
        doReturn(new PayoutTransferResult.Unknown(id, "NO_RESPONSE")).when(client).find(any());
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);

        timeShift = AFTER_SCHEDULE.plus(STALE);
        assertThat(payoutUseCase.recoverStale()).isEqualTo(new ExchangePayoutRecoveryResult(1, 0, 0, 1, 0, 0));
        // 1분이 지나지 않았으면 다시 확인하지 않는다
        assertThat(payoutUseCase.recoverStale().targets()).isZero();
        timeShift = AFTER_SCHEDULE.plus(STALE).plus(STALE);
        assertThat(payoutUseCase.recoverStale().inProgress()).isEqualTo(1);
        assertThat(payoutRows(exchangeId).getFirst()).containsEntry("status", "UNKNOWN")
                .containsEntry("result_check_count", 2).containsEntry("manual_review_at", null);
        Mockito.clearInvocations(client);

        timeShift = AFTER_SCHEDULE.plus(Duration.ofHours(24)).plusMinutes(1);
        ExchangePayoutRecoveryResult expired = payoutUseCase.recoverStale();

        assertThat(expired).isEqualTo(new ExchangePayoutRecoveryResult(1, 0, 0, 0, 1, 0));
        verify(client, never()).find(any());
        verify(client, never()).send(any());
        assertThat(payoutRows(exchangeId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "MANUAL_REVIEW_REQUIRED")
                    .containsEntry("failure_reason", "AUTO_CHECK_EXPIRED").containsEntry("result_check_count", 2);
            assertThat(row.get("manual_review_at")).isNotNull();
        });
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "UNKNOWN").containsEntry("failed_at", null);
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD").containsEntry("held_revenue_hp", 15_000L);
        assertThat(releaseKeys(exchangeId)).isEmpty();
        assertWallet(walletId, 5_000L, 15_000L);

        // 운영자 확인 대상은 자동 확인·실행·재송금하지 않는다
        timeShift = timeShift.plus(STALE);
        assertThat(payoutUseCase.recoverStale().targets()).isZero();
        assertThat(payoutUseCase.execute(exchangeId).action()).isEqualTo(ExchangePayoutAction.NONE);
        verify(client, never()).send(any());
        assertThat(count("billing.exchange_payout")).isEqualTo(1);
    }

    @Test
    @DisplayName("24시간 안에 결과가 확인되면 횟수와 관계없이 성공으로 확정한다")
    void resolvedWithinLimit() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        plan(MockPayoutScenario.TIMEOUT);
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);

        timeShift = AFTER_SCHEDULE.plus(Duration.ofHours(23)).plusMinutes(59);
        assertThat(payoutUseCase.recoverStale().completed()).isEqualTo(1);
        assertThat(payoutRows(exchangeId).getFirst()).containsEntry("status", "COMPLETED")
                .containsEntry("result_check_count", 1);
        assertWallet(walletId, 5_000L, 0L);
    }

    // ---------------------------------------------------------------------------------------------
    // 관리자 결과 재확인·수동 재송금
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("기한 초과로 운영자 확인이 된 건도 은행이 실제로 성공했다면 관리자 재확인(같은 ID 조회)으로 성공·확정 차감하고 HP를 "
            + "반환하지 않는다. 최종 실패로 종료할 수 없다")
    void recheckFindsSuccessAfterExpiry() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.TIMEOUT).getFirst();
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);
        timeShift = AFTER_SCHEDULE.plus(Duration.ofHours(25));
        payoutUseCase.recoverStale();
        assertThat(payoutRows(exchangeId).getFirst()).containsEntry("status", "MANUAL_REVIEW_REQUIRED");
        assertBillingError(() -> payoutUseCase.failFinally(ADMIN, exchangeId, "사유"),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);

        ExchangePayoutResult rechecked = payoutUseCase.recheck(ADMIN, exchangeId);

        assertThat(rechecked.exchangeStatus()).isEqualTo(ExchangeStatus.COMPLETED);
        assertThat(rechecked.payoutRequestId()).isEqualTo(id);
        assertThat(payoutRows(exchangeId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "COMPLETED").containsEntry("manual_checked_by", ADMIN)
                    .containsEntry("failure_reason", null);
            assertThat(row.get("manual_review_at")).isNotNull();
            assertThat(row.get("manual_checked_at")).isNotNull();
        });
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);
        assertThat(captureKeys(exchangeId)).hasSize(1);
        assertThat(releaseKeys(exchangeId)).isEmpty();
        assertWallet(walletId, 5_000L, 0L);
        assertBillingError(() -> payoutUseCase.recheck(ADMIN, exchangeId),
                BillingErrorCode.EXCHANGE_PAYOUT_NOT_CHECKABLE);
    }

    @Test
    @DisplayName("은행 거절(4xx)로 운영자 확인이 된 건: 관리자 재확인에서 은행 기록 없음이 1분 뒤에 확인되면 FAILED(BANK_RECORD_NOT_FOUND)로 "
            + "확정하고, 그 뒤 관리자 재송금(새 ID)으로 성공할 수 있다. 1분 안의 재확인은 상태를 바꾸지 않는다")
    void recheckRejectedThenRetry() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        List<String> ids = plan(MockPayoutScenario.SUCCESS, MockPayoutScenario.SUCCESS);
        doReturn(new PayoutTransferResult.Rejected(ids.getFirst(), 400, "MOCK_PAYOUT_INVALID_REQUEST")).when(client)
                .send(any());
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);
        assertThat(payoutRows(exchangeId).getFirst()).containsEntry("status", "MANUAL_REVIEW_REQUIRED");

        // 방금 보낸 시도는 은행 기록이 없다는 것만으로 실패로 확정하지 않는다
        assertThat(payoutUseCase.recheck(ADMIN, exchangeId).payoutStatus())
                .isEqualTo(ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED);

        timeShift = AFTER_SCHEDULE.plus(STALE);
        ExchangePayoutResult rechecked = payoutUseCase.recheck(OTHER_ADMIN, exchangeId);

        assertThat(rechecked.exchangeStatus()).isEqualTo(ExchangeStatus.FAILED);
        assertThat(rechecked.payoutStatus()).isEqualTo(ExchangePayoutStatus.FAILED);
        assertThat(rechecked.failureReason()).isEqualTo("BANK_RECORD_NOT_FOUND");
        assertThat(payoutRows(exchangeId).getFirst()).containsEntry("manual_checked_by", OTHER_ADMIN);
        assertThat(holdOf(exchangeId)).containsEntry("status", "HELD");
        assertWallet(walletId, 5_000L, 15_000L);

        doCallRealMethod().when(client).send(any());
        ExchangePayoutResult retried = payoutUseCase.retryFailed(ADMIN, exchangeId);

        assertThat(retried.exchangeStatus()).isEqualTo(ExchangeStatus.COMPLETED);
        assertThat(retried.attemptNo()).isEqualTo(2);
        assertThat(retried.payoutRequestId()).isEqualTo(ids.get(1));
        assertThat(captureKeys(exchangeId)).hasSize(1);
        assertWallet(walletId, 5_000L, 0L);
    }

    @Test
    @DisplayName("결과 대기(UNKNOWN) 건의 관리자 재확인은 조회만 한다: 은행 기록이 없어도 다시 보내지 않고 UNKNOWN으로 둔다")
    void recheckInFlightDoesNotResend() {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        plan(MockPayoutScenario.SERVER_ERROR);
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);
        Mockito.clearInvocations(client);

        ExchangePayoutResult rechecked = payoutUseCase.recheck(ADMIN, exchangeId);

        assertThat(rechecked.exchangeStatus()).isEqualTo(ExchangeStatus.UNKNOWN);
        assertThat(rechecked.payoutStatus()).isEqualTo(ExchangePayoutStatus.UNKNOWN);
        assertThat(rechecked.failureReason()).isEqualTo("NOT_FOUND");
        verify(client, never()).send(any());
        assertThat(count("billing.mock_payout_transfer")).isZero();
        assertThat(payoutRows(exchangeId).getFirst()).containsEntry("result_check_count", 0);
        assertWallet(walletId, 5_000L, 15_000L);
    }

    @Test
    @DisplayName("송금 전(APPROVED)·성공(COMPLETED)·확정 실패(FAILED) 건은 결과 재확인 대상이 아니다(EXCHANGE_PAYOUT_NOT_CHECKABLE)")
    void recheckNotCheckable() {
        influencer(30_000L);
        Long approved = approvedExchange(10_000L);
        Long completed = approvedExchange(10_000L);
        Long failed = approvedExchange(5_000L);
        plan(MockPayoutScenario.SUCCESS, MockPayoutScenario.FAILED);
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(completed);
        payoutUseCase.execute(failed);

        for (Long exchangeId : List.of(approved, completed, failed)) {
            assertBillingError(() -> payoutUseCase.recheck(ADMIN, exchangeId),
                    BillingErrorCode.EXCHANGE_PAYOUT_NOT_CHECKABLE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 관리자 처리 동시성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("FAILED 환전에 최종 실패와 재송금이 동시에 와도 하나만 성공한다: 반환과 확정 차감 중 하나만 일어나고 둘 다 일어나지 않는다")
    void finalizeVersusRetry() throws Exception {
        for (int round = 0; round < 3; round++) {
            Long walletId = walletRepository.findByUserId(INFLUENCER).map(Wallet::getId).orElse(null);
            if (walletId == null) {
                walletId = influencer(50_000L);
            }
            Long exchangeId = approvedExchange(10_000L);
            plan(MockPayoutScenario.FAILED, MockPayoutScenario.SUCCESS);
            timeShift = AFTER_SCHEDULE;
            payoutUseCase.execute(exchangeId);

            List<Object> outcomes = runConcurrently(List.of(
                    () -> payoutUseCase.failFinally(ADMIN, exchangeId, "종료"),
                    () -> payoutUseCase.retryFailed(OTHER_ADMIN, exchangeId)));

            String status = (String) exchangeRow(exchangeId).get("status");
            assertThat(status).isIn("FINAL_FAILED", "COMPLETED");
            assertThat(releaseKeys(exchangeId).size() + captureKeys(exchangeId).size()).isEqualTo(1);
            if ("FINAL_FAILED".equals(status)) {
                assertThat(outcomes).contains(BillingErrorCode.EXCHANGE_PAYOUT_NOT_RETRYABLE);
                assertThat(count("billing.exchange_payout WHERE exchange_id = " + exchangeId)).isEqualTo(1);
            } else {
                assertThat(outcomes).contains(BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
            }
            assertConsistent(walletId);
        }
    }

    @Test
    @DisplayName("같은 최종 실패 요청이 동시에 여러 번 와도 반환은 한 번이다")
    void concurrentSameFinalize() throws Exception {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        plan(MockPayoutScenario.FAILED);
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);

        List<Object> outcomes = runConcurrently(List.of(
                () -> payoutUseCase.failFinally(ADMIN, exchangeId, "종료"),
                () -> payoutUseCase.failFinally(ADMIN, exchangeId, "종료"),
                () -> payoutUseCase.failFinally(ADMIN, exchangeId, "종료"),
                () -> payoutUseCase.failFinally(ADMIN, exchangeId, "종료")));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(ExchangePayoutResult.class));
        assertThat(releaseKeys(exchangeId)).hasSize(1);
        assertWallet(walletId, 20_000L, 0L);
    }

    @Test
    @DisplayName("관리자 재확인과 자동 결과 확인이 동시에 같은 성공을 받아도 확정 차감은 한 번이다")
    void recheckVersusRecovery() throws Exception {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        plan(MockPayoutScenario.TIMEOUT);
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);
        timeShift = AFTER_SCHEDULE.plus(STALE);

        runConcurrently(List.of(() -> payoutUseCase.recheck(ADMIN, exchangeId), () -> payoutUseCase.recoverStale(),
                () -> payoutUseCase.recheck(OTHER_ADMIN, exchangeId)));

        assertThat(exchangeRow(exchangeId)).containsEntry("status", "COMPLETED");
        assertThat(captureKeys(exchangeId)).hasSize(1);
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);
        assertWallet(walletId, 5_000L, 0L);
    }

    // ---------------------------------------------------------------------------------------------
    // 관리자 API
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("관리자 API: 운영자 확인 대상·시도 이력 조회, 결과 재확인, 수동 재송금, 최종 실패 확정(RsData). 계좌번호는 응답에 없다")
    void adminApi() throws Exception {
        Long walletId = influencer(30_000L);
        Long review = approvedExchange(10_000L);
        Long failed = approvedExchange(10_000L);
        List<String> ids = plan(MockPayoutScenario.SUCCESS, MockPayoutScenario.FAILED, MockPayoutScenario.SUCCESS);
        doReturn(new PayoutTransferResult.Rejected(ids.getFirst(), 400, "MOCK_PAYOUT_INVALID_REQUEST"))
                .doCallRealMethod().when(client).send(any());
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(review);
        payoutUseCase.execute(failed);

        String reviews = mockMvc.perform(get(PATH + "/payouts").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("환전 송금 시도 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].exchangeId").value(review))
                .andExpect(jsonPath("$.data.content[0].status").value("MANUAL_REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.content[0].exchangeStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.content[0].payoutRequestId").value(ids.getFirst()))
                .andExpect(jsonPath("$.data.content[0].failureReason").value("REJECTED_400:MOCK_PAYOUT_INVALID_REQUEST"))
                .andExpect(jsonPath("$.data.content[0].manualReviewAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(reviews).doesNotContain(ACCOUNT);
        mockMvc.perform(get(PATH + "/payouts").param("status", "FAILED").with(admin()))
                .andExpect(jsonPath("$.data.content[0].exchangeId").value(failed))
                .andExpect(jsonPath("$.data.content[0].failureReason").value("MOCK_TRANSFER_REJECTED"));
        mockMvc.perform(get(PATH).param("status", "FAILED").with(admin()))
                .andExpect(jsonPath("$.data.content[0].exchangeId").value(failed))
                .andExpect(jsonPath("$.data.content[0].failureReason")
                        .value("MOCK_PAYOUT_FAILED:MOCK_TRANSFER_REJECTED"));

        // 결과 재확인(1분 뒤, 은행 기록 없음 → FAILED)
        timeShift = AFTER_SCHEDULE.plus(STALE);
        mockMvc.perform(post(PATH + "/{id}/payouts/recheck", review).with(csrf()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("환전 송금 결과를 다시 확인했습니다."))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("BANK_RECORD_NOT_FOUND"))
                .andExpect(jsonPath("$.data.manualCheckedBy").value(ADMIN));

        // 수동 재송금(새 ID) → 성공
        mockMvc.perform(post(PATH + "/{id}/payouts/retry", review).with(csrf()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptNo").value(2))
                .andExpect(jsonPath("$.data.payoutRequestId").value(ids.get(2)))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.exchangeStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.requestedBy").value(ADMIN));
        mockMvc.perform(get(PATH + "/{id}/payouts", review).with(admin()))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[1].status").value("COMPLETED"));
        mockMvc.perform(post(PATH + "/{id}/payouts/retry", review).with(csrf()).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXCHANGE_PAYOUT_NOT_RETRYABLE"));

        // 최종 실패 확정 → 보류 반환
        mockMvc.perform(finalFailure(failed, "{\"reason\":\"  \"}")).andExpect(status().isBadRequest());
        mockMvc.perform(finalFailure(failed, "{\"reason\":\"계좌 오류\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("환전을 최종 실패로 종료하고 보류한 HP를 반환했습니다."))
                .andExpect(jsonPath("$.data.status").value("FINAL_FAILED"))
                .andExpect(jsonPath("$.data.finalFailedBy").value(ADMIN))
                .andExpect(jsonPath("$.data.finalFailureReason").value("계좌 오류"))
                .andExpect(jsonPath("$.data.account.accountNumberMasked").value("**********1234"));
        mockMvc.perform(finalFailure(review, "{\"reason\":\"계좌 오류\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXCHANGE_PAYOUT_NOT_FINALIZABLE"));
        mockMvc.perform(get(PATH + "/{id}/payouts", 999_999L).with(admin())).andExpect(status().isNotFound());
        // 성공 10,000 확정 차감, 최종 실패 10,000 반환 → 수익 HP 20,000, 보류 0
        assertWallet(walletId, 20_000L, 0L);
        assertConsistent(walletId);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "INFLUENCER", "VERIFIED_INFLUENCER"})
    @DisplayName("관리자 API: 관리자가 아니면 403, 비로그인은 401이고 송금·반환이 일어나지 않는다")
    void adminApiAuthorization(Role role) throws Exception {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        plan(MockPayoutScenario.FAILED);
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);
        Mockito.clearInvocations(client);

        for (var request : List.of(get(PATH + "/payouts"), get(PATH + "/{id}/payouts", exchangeId),
                post(PATH + "/{id}/payouts/recheck", exchangeId).with(csrf()),
                post(PATH + "/{id}/payouts/retry", exchangeId).with(csrf()),
                post(PATH + "/{id}/payouts/final-failure", exchangeId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사유\"}"))) {
            mockMvc.perform(request.with(TestLogin.as(INFLUENCER, role))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get(PATH + "/payouts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH + "/{id}/payouts/retry", exchangeId).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH + "/{id}/payouts/final-failure", exchangeId).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사유\"}"))
                .andExpect(status().isUnauthorized());

        verify(client, never()).send(any());
        verify(client, never()).find(any());
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "FAILED");
        assertThat(count("billing.exchange_payout")).isEqualTo(1);
        assertWallet(walletId, 5_000L, 15_000L);
    }

    // ---------------------------------------------------------------------------------------------
    // 동시 실행·여러 건
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 환전을 동시에 여러 번 실행해도 시도 1건·Mock 송금 1건·확정 차감 1회이고 COMPLETED다")
    void concurrentExecute() throws Exception {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        plan(MockPayoutScenario.DELAY);
        timeShift = AFTER_SCHEDULE;

        List<Object> outcomes = runConcurrently(List.of(
                () -> payoutUseCase.execute(exchangeId), () -> payoutUseCase.execute(exchangeId),
                () -> payoutUseCase.execute(exchangeId), () -> payoutUseCase.retryFailed(ADMIN, exchangeId),
                () -> payoutUseCase.recoverStale()));

        assertThat(outcomes).filteredOn(ExchangePayoutResult.class::isInstance)
                .extracting(outcome -> ((ExchangePayoutResult) outcome).action())
                .containsOnlyOnce(ExchangePayoutAction.SENT);
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "COMPLETED");
        assertThat(count("billing.exchange_payout")).isEqualTo(1);
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);
        assertThat(captureKeys(exchangeId)).hasSize(1);
        assertWallet(walletId, 5_000L, 0L);
    }

    @Test
    @DisplayName("결과 대기 건의 결과 확인이 동시에 여러 번 와도 한 실행만 확인하고(나머지 NONE) 재송금은 최대 1번이다")
    void concurrentRecovery() throws Exception {
        Long walletId = influencer(20_000L);
        Long exchangeId = approvedExchange(15_000L);
        String id = plan(MockPayoutScenario.SERVER_ERROR).getFirst();
        timeShift = AFTER_SCHEDULE;
        payoutUseCase.execute(exchangeId);
        timeShift = AFTER_SCHEDULE.plus(STALE);

        List<Object> outcomes = runConcurrently(List.of(
                () -> payoutUseCase.execute(exchangeId), () -> payoutUseCase.execute(exchangeId),
                () -> payoutUseCase.execute(exchangeId), () -> payoutUseCase.execute(exchangeId)));

        assertThat(outcomes).extracting(outcome -> ((ExchangePayoutResult) outcome).action())
                .containsOnlyOnce(ExchangePayoutAction.RESENT).containsOnly(ExchangePayoutAction.RESENT,
                        ExchangePayoutAction.NONE);
        assertThat(transfer(id)).containsEntry("status", "SUCCEEDED");
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(1);
        assertThat(captureKeys(exchangeId)).hasSize(1);
        assertWallet(walletId, 5_000L, 0L);
    }

    @Test
    @DisplayName("여러 건 결과 확인: 한 건의 조회가 예외여도 나머지는 처리하고, 예외 건은 다음 실행에서 다시 확인한다")
    void recoveryIsolatesFailures() {
        Long walletId = influencer(30_000L);
        Long first = approvedExchange(10_000L);
        Long second = approvedExchange(10_000L);
        Long third = approvedExchange(5_000L);
        List<String> ids = plan(MockPayoutScenario.TIMEOUT, MockPayoutScenario.TIMEOUT, MockPayoutScenario.TIMEOUT);
        timeShift = AFTER_SCHEDULE;
        List.of(first, second, third).forEach(payoutUseCase::execute);
        doThrow(new IllegalStateException("조회 장애 시뮬레이션")).when(client).find(eq(ids.get(1)));
        timeShift = AFTER_SCHEDULE.plus(STALE);

        ExchangePayoutRecoveryResult recovered = payoutUseCase.recoverStale();

        assertThat(recovered).isEqualTo(new ExchangePayoutRecoveryResult(3, 2, 0, 0, 0, 1));
        assertThat(exchangeRow(first)).containsEntry("status", "COMPLETED");
        assertThat(exchangeRow(second)).containsEntry("status", "UNKNOWN");
        assertThat(exchangeRow(third)).containsEntry("status", "COMPLETED");

        // 다음 실행(대기 시간 뒤)에서 남은 건을 확인한다
        doCallRealMethod().when(client).find(any());
        timeShift = AFTER_SCHEDULE.plus(STALE).plus(STALE);
        assertThat(payoutUseCase.recoverStale()).isEqualTo(new ExchangePayoutRecoveryResult(1, 1, 0, 0, 0, 0));
        assertThat(count("billing.mock_payout_transfer")).isEqualTo(3);
        assertWallet(walletId, 5_000L, 0L);
    }

    @Test
    @DisplayName("여러 승인 건을 차례로 송금하면 한 건의 확정 실패가 다른 건의 성공을 막지 않는다")
    void multipleExchanges() {
        Long walletId = influencer(30_000L);
        Long first = approvedExchange(10_000L);
        Long second = approvedExchange(10_000L);
        Long third = approvedExchange(5_000L);
        plan(MockPayoutScenario.SUCCESS, MockPayoutScenario.FAILED, MockPayoutScenario.SUCCESS);
        timeShift = AFTER_SCHEDULE;

        List<ExchangeStatus> statuses = List.of(first, second, third).stream()
                .map(id -> payoutUseCase.execute(id).exchangeStatus()).toList();

        assertThat(statuses).containsExactly(ExchangeStatus.COMPLETED, ExchangeStatus.FAILED, ExchangeStatus.COMPLETED);
        // 실패 건(10,000 HP)만 보류로 남는다
        assertWallet(walletId, 5_000L, 10_000L);
        assertConsistent(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    // 신청자 지갑 + 수익 HP + 회원 복제본(정상 인플루언서)
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

    private static RequestPostProcessor admin() {
        return TestLogin.as(ADMIN, Role.ADMIN);
    }

    private static MockHttpServletRequestBuilder finalFailure(Long exchangeId, String body) {
        return post(PATH + "/{id}/payouts/final-failure", exchangeId).with(csrf()).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void assertWallet(Long walletId, long revenue, long heldRevenue) {
        assertThat(walletRepository.findById(walletId).orElseThrow())
                .extracting(Wallet::getRevenueBalanceHp, Wallet::getHeldRevenueHp).containsExactly(revenue, heldRevenue);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    // 환전별: COMPLETED면 보류 확정 차감(원장 합 = -신청 금액), FINAL_FAILED면 보류 전액 반환(원장 합 0, 반환 1회),
    // 그 밖(시도 실패·결과 대기)은 보류 전액 유지(원장 합 0). 확정 차감과 반환은 함께 일어나지 않는다
    private void assertConsistent(Long walletId) {
        for (Map<String, Object> exchange : jdbcTemplate.queryForList("SELECT * FROM billing.exchange")) {
            Long id = (Long) exchange.get("id");
            long requested = (Long) exchange.get("requested_amount_hp");
            long ledgerSum = jdbcTemplate.queryForObject("""
                    SELECT coalesce(sum(amount_hp), 0) FROM billing.point_ledger
                    WHERE reference_type = 'EXCHANGE' AND reference_id = ?""", Long.class, id);
            if ("COMPLETED".equals(exchange.get("status"))) {
                assertThat(holdOf(id)).containsEntry("status", "CAPTURED").containsEntry("held_revenue_hp", 0L);
                assertThat(ledgerSum).isEqualTo(-requested);
                assertThat(releaseKeys(id)).isEmpty();
            } else if ("FINAL_FAILED".equals(exchange.get("status"))) {
                assertThat(holdOf(id)).containsEntry("status", "RELEASED").containsEntry("held_revenue_hp", 0L);
                assertThat(ledgerSum).isZero();
                assertThat(releaseKeys(id)).hasSize(1);
                assertThat(captureKeys(id)).isEmpty();
            } else {
                assertThat(holdOf(id)).containsEntry("status", "HELD").containsEntry("held_revenue_hp", requested);
                assertThat(ledgerSum).isZero();
            }
        }
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    private Map<String, Object> exchangeRow(Long exchangeId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.exchange WHERE id = ?", exchangeId);
    }

    private List<Map<String, Object>> payoutRows(Long exchangeId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM billing.exchange_payout WHERE exchange_id = ? ORDER BY attempt_no", exchangeId);
    }

    private Map<String, Object> transfer(String payoutRequestId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.mock_payout_transfer WHERE payout_request_id = ?",
                payoutRequestId);
    }

    private Map<String, Object> holdOf(Long exchangeId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM billing.point_hold WHERE reference_type = 'EXCHANGE' AND reference_id = ?", exchangeId);
    }

    private List<String> captureKeys(Long exchangeId) {
        return detailKeys(exchangeId, "CAPTURE");
    }

    private List<String> releaseKeys(Long exchangeId) {
        return detailKeys(exchangeId, "RELEASE");
    }

    private List<String> detailKeys(Long exchangeId, String actionType) {
        return jdbcTemplate.queryForList("""
                SELECT d.idempotency_key FROM billing.point_hold_detail d
                JOIN billing.point_hold h ON h.id = d.point_hold_id
                WHERE h.reference_type = 'EXCHANGE' AND h.reference_id = ? AND d.action_type = ?""",
                String.class, exchangeId, actionType);
    }

    private List<String> ledgerOf(Long exchangeId) {
        return jdbcTemplate.queryForList("""
                SELECT point_type || ' ' || balance_bucket || ' ' || amount_hp || ' ' || transaction_type
                FROM billing.point_ledger WHERE reference_type = 'EXCHANGE' AND reference_id = ? ORDER BY id""",
                String.class, exchangeId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    // 작업을 동시에 시작해 결과(성공 값 또는 BillingErrorCode)를 모은다.
    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (BillingException exception) {
                        return exception.getErrorCode();
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertBillingError(Runnable action, BillingErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BillingException.class)
                .extracting(exception -> ((BillingException) exception).getErrorCode()).isEqualTo(code);
    }
}
