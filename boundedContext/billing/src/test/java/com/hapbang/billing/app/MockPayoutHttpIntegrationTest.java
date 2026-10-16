package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.in.MockPayoutController;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.out.MockPayoutAccessKey;
import com.hapbang.billing.out.MockPayoutHttpClient;
import com.hapbang.billing.out.MockPayoutProperties;
import com.hapbang.billing.out.MockPayoutScenario;
import com.hapbang.billing.out.MockPayoutTransferRepository;
import com.hapbang.billing.out.PaymentKeyCipher;
import com.hapbang.billing.out.PayoutTransferRequest;
import com.hapbang.billing.out.PayoutTransferResult;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

// 가상 송금(#288)을 실제 HTTP로 검증한다: 랜덤 포트로 서버를 띄우고, 가상 송금 HTTP Client가 같은 애플리케이션의 Mock 은행 API에
// 실제 요청을 보낸다(메서드 직접 호출 없음). 외부 은행·API 주소는 없다(Client는 localhost:{이 서버 포트}만 부른다).
// 시간 제한: 응답 1초, DELAY 0.3초(제한 안), TIMEOUT 2.5초(제한 초과).
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
                // global 내부 API 보안(InternalApiSecurityConfig)이 키를 요구한다 (#300)
                "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY,
                "hapbang.billing.mock-payout.enabled=true",
                "hapbang.billing.mock-payout.connect-timeout=1s",
                "hapbang.billing.mock-payout.read-timeout=1s",
                "hapbang.billing.mock-payout.delay=300ms",
                "hapbang.billing.mock-payout.timeout-delay=2500ms",
                // 이 클래스만 쓰는 컨텍스트(실제 서버)라 연결 풀을 작게 두고 끝나면 닫는다(다른 테스트 컨텍스트와 함께 DB 최대 연결 수를 넘지 않게)
                "spring.datasource.hikari.maximum-pool-size=5",
                "spring.datasource.hikari.minimum-idle=1"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// Mock은 활성 프로필이 local·test일 때만 켜진다(MockPayoutActivation)
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class MockPayoutHttpIntegrationTest {

    private static final String ACCOUNT = "12345678901234";
    private static final String HOLDER = "홍길동";

    @DynamicPropertySource
    static void cipherProperties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.billing.payment-key.encryption-key", () -> ChargeFixture.ENCRYPTION_KEY);
        registry.add("hapbang.billing.payment-key.hmac-key", () -> ChargeFixture.HMAC_KEY);
    }

    @LocalServerPort
    int port;

    @Autowired
    MockPayoutHttpClient client;

    @Autowired
    MockPayoutScenarios scenarios;

    @Autowired
    ExchangeRequestUseCase exchangeRequestUseCase;

    @Autowired
    ExchangeAccountCipher exchangeAccountCipher;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    MockPayoutAccessKey accessKey;

    // 시나리오는 지급 요청 ID(테스트마다 새 UUID)별로만 저장되므로 테스트끼리 섞이지 않는다. 전체를 지우는 정리(clear)는 병렬 실행 시
    // 다른 테스트의 시나리오를 지울 수 있어 하지 않는다. 남은 값은 이 클래스가 끝날 때 컨텍스트와 함께 닫힌다(@DirtiesContext).

    @Autowired
    RestClient.Builder restClientBuilder;

    @Autowired
    Environment environment;

    @Autowired
    MockPayoutProperties mockPayoutProperties;

    @Autowired
    MockPayoutTransferRepository mockPayoutTransferRepository;

    @Autowired
    PaymentKeyCipher paymentKeyCipher;

    @Autowired
    ApplicationContext applicationContext;

    // ---------------------------------------------------------------------------------------------
    // 시나리오
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("SUCCESS(기본): 실제 HTTP로 송금하면 Succeeded(거래 번호)이고, 같은 ID 조회도 같은 성공 결과다")
    void success() {
        PayoutTransferRequest request = request(1L, 18_000L);

        PayoutTransferResult result = client.send(request);

        assertThat(result).isInstanceOfSatisfying(PayoutTransferResult.Succeeded.class, succeeded -> {
            assertThat(succeeded.transactionId()).startsWith("MOCKTX-");
            assertThat(succeeded.amountKrw()).isEqualTo(18_000L);
            assertThat(succeeded.completedAt()).isNotNull();
        });
        assertThat(client.find(request.payoutRequestId())).isEqualTo(result);
        Map<String, Object> row = transfer(request.payoutRequestId());
        assertThat(row).containsEntry("status", "SUCCEEDED").containsEntry("exchange_id", 1L)
                .containsEntry("amount_krw", 18_000L).containsEntry("bank_code", "004")
                .containsEntry("account_holder_name", HOLDER);
        // 계좌번호 원문은 저장하지 않는다(HMAC 64자만)
        assertThat((String) row.get("account_number_hash")).hasSize(64).doesNotContain(ACCOUNT);
        assertThat(row.values()).allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(ACCOUNT));
    }

    @Test
    @DisplayName("FAILED: 가상 송금 확정 실패는 Failed(MOCK_TRANSFER_REJECTED)이고 장애(Unknown)와 구분된다. 조회도 같은 실패다")
    void failed() {
        PayoutTransferRequest request = request(1L, 9_000L);
        scenarios.set(request.payoutRequestId(), MockPayoutScenario.FAILED);

        PayoutTransferResult result = client.send(request);

        assertThat(result).isInstanceOfSatisfying(PayoutTransferResult.Failed.class,
                failed -> assertThat(failed.failureCode()).isEqualTo("MOCK_TRANSFER_REJECTED"));
        assertThat(client.find(request.payoutRequestId())).isEqualTo(result);
        // 다시 보내도 저장된 실패다(새로 처리하지 않는다)
        assertThat(client.send(request)).isEqualTo(result);
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DELAY: 응답 시간 제한 안의 지연은 기다렸다가 Succeeded를 받는다")
    void delayWithinTimeout() {
        PayoutTransferRequest request = request(1L, 9_000L);
        scenarios.set(request.payoutRequestId(), MockPayoutScenario.DELAY);
        long started = System.nanoTime();

        PayoutTransferResult result = client.send(request);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(result).isInstanceOf(PayoutTransferResult.Succeeded.class);
    }

    @Test
    @DisplayName("TIMEOUT: 처리 후 응답이 시간 제한을 넘으면 Unknown(실패 아님)이고, 같은 ID 조회로 성공을 복구한다. 같은 ID 재전송도 중복 처리하지 않는다")
    void timeoutThenRecoverByLookup() {
        PayoutTransferRequest request = request(1L, 9_000L);
        scenarios.set(request.payoutRequestId(), MockPayoutScenario.TIMEOUT);

        PayoutTransferResult result = client.send(request);

        assertThat(result).isEqualTo(new PayoutTransferResult.Unknown(request.payoutRequestId(), "NO_RESPONSE"));
        PayoutTransferResult recovered = client.find(request.payoutRequestId());
        assertThat(recovered).isInstanceOf(PayoutTransferResult.Succeeded.class);
        assertThat(client.send(request)).isEqualTo(recovered);
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SERVER_ERROR: HTTP 5xx는 Unknown이고 처리되지 않아 조회는 NotFound다. 같은 ID로 다시 보내면 처리된다(새 ID 아님)")
    void serverErrorThenNotFound() {
        PayoutTransferRequest request = request(1L, 9_000L);
        scenarios.set(request.payoutRequestId(), MockPayoutScenario.SERVER_ERROR);

        PayoutTransferResult result = client.send(request);

        assertThat(result).isEqualTo(new PayoutTransferResult.Unknown(request.payoutRequestId(),
                "500:MOCK_PAYOUT_SERVER_ERROR"));
        assertThat(client.find(request.payoutRequestId()))
                .isEqualTo(new PayoutTransferResult.NotFound(request.payoutRequestId()));
        assertThat(transferCount()).isZero();
        assertThat(client.send(request)).isInstanceOf(PayoutTransferResult.Succeeded.class);
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 지급 요청 ID 조회는 NotFound다(실패·재송금으로 단정하지 않는다)")
    void notFound() {
        String id = UUID.randomUUID().toString();

        assertThat(client.find(id)).isEqualTo(new PayoutTransferResult.NotFound(id));
    }

    // ---------------------------------------------------------------------------------------------
    // 멱등성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 지급 요청 ID·같은 내용의 반복 요청은 처음 결과를 그대로 돌려주고 한 번만 처리한다")
    void sameRequestRepeated() {
        PayoutTransferRequest request = request(1L, 18_000L);

        PayoutTransferResult first = client.send(request);
        PayoutTransferResult second = client.send(request);
        PayoutTransferResult third = client.send(new PayoutTransferRequest(request.payoutRequestId(), 1L, 18_000L,
                "004", ACCOUNT, HOLDER));

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 지급 요청 ID에 환전 ID·금액·은행·계좌번호·예금주가 다르면 409 Conflict이고 처음 기록은 그대로다")
    void sameIdDifferentRequestConflicts() {
        PayoutTransferRequest original = request(1L, 18_000L);
        PayoutTransferResult first = client.send(original);
        String id = original.payoutRequestId();

        List<PayoutTransferRequest> different = List.of(
                new PayoutTransferRequest(id, 2L, 18_000L, "004", ACCOUNT, HOLDER),
                new PayoutTransferRequest(id, 1L, 18_001L, "004", ACCOUNT, HOLDER),
                new PayoutTransferRequest(id, 1L, 18_000L, "088", ACCOUNT, HOLDER),
                new PayoutTransferRequest(id, 1L, 18_000L, "004", "12345678901235", HOLDER),
                new PayoutTransferRequest(id, 1L, 18_000L, "004", ACCOUNT, "김철수"));

        for (PayoutTransferRequest request : different) {
            assertThat(client.send(request))
                    .isEqualTo(new PayoutTransferResult.Conflict(id, "MOCK_PAYOUT_IDEMPOTENCY_CONFLICT"));
        }
        assertThat(client.find(id)).isEqualTo(first);
        assertThat(transferCount()).isEqualTo(1);
        assertThat(transfer(id)).containsEntry("exchange_id", 1L).containsEntry("amount_krw", 18_000L);
    }

    @Test
    @DisplayName("같은 지급 요청 ID 동시 요청은 한 번만 처리한다: 나머지는 처리 중(Processing) 또는 같은 성공 결과이고 충돌·불명확이 없다")
    void concurrentSameRequest() throws Exception {
        PayoutTransferRequest request = request(1L, 18_000L);
        scenarios.set(request.payoutRequestId(), MockPayoutScenario.DELAY);

        List<PayoutTransferResult> results = runConcurrently(8, () -> client.send(request));

        assertThat(transferCount()).isEqualTo(1);
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOfAny(
                PayoutTransferResult.Succeeded.class, PayoutTransferResult.Processing.class));
        assertThat(results).filteredOn(PayoutTransferResult.Succeeded.class::isInstance).isNotEmpty()
                .extracting(result -> ((PayoutTransferResult.Succeeded) result).transactionId()).containsOnly(
                        ((PayoutTransferResult.Succeeded) client.find(request.payoutRequestId())).transactionId());
    }

    // ---------------------------------------------------------------------------------------------
    // 금융 트랜잭션 분리·보안
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("DB 트랜잭션 안에서는 HTTP를 보내기 전에 거절한다(락을 쥔 채 같은 애플리케이션을 기다리지 않는다)")
    void rejectsCallInsideTransaction() {
        PayoutTransferRequest request = request(1L, 18_000L);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> client.send(request)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("트랜잭션 밖");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> client.find(request.payoutRequestId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("접근 키가 없거나 틀리면 Mock API는 컨트롤러 전에 403이고 아무것도 기록하지 않는다. 키·본문 ID가 다르면 400이다")
    void accessKeyRequired() {
        RestClient raw = RestClient.create("http://localhost:" + port);
        String id = UUID.randomUUID().toString();
        String body = "{\"payoutRequestId\":\"" + id + "\",\"exchangeId\":1,\"amountKrw\":1000,\"bankCode\":\"004\","
                + "\"accountNumber\":\"" + ACCOUNT + "\",\"accountHolderName\":\"" + HOLDER + "\"}";

        assertThat(rawPost(raw, id, null, body).value()).isEqualTo(403);
        assertThat(rawPost(raw, id, "wrong-key", body).value()).isEqualTo(403);
        assertThat(raw.get().uri("/api/v1/wallets/mock-payouts/{id}", id).exchange((req, res) -> res.getStatusCode())
                .value()).isEqualTo(403);
        assertThat(transferCount()).isZero();
        // 전용 보안 체인은 Mock 경로에만 적용된다: 다른 API는 여전히 로그인이 필요하다(접근 키로도 열리지 않는다)
        assertThat(raw.get().uri("/api/v1/wallets/me").header(MockPayoutAccessKey.HEADER, accessKeyValue())
                .exchange((req, res) -> res.getStatusCode()).value()).isEqualTo(401);

        String mismatch = body.replace("\"payoutRequestId\":\"" + id, "\"payoutRequestId\":\"" + UUID.randomUUID());
        assertThat(rawPost(raw, id, accessKeyValue(), mismatch).value()).isEqualTo(400);
        assertThat(transferCount()).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("계좌번호는 성공·멱등키 충돌·5xx 어느 경우에도 로그와 요청 toString에 남지 않는다")
    void accountNumberNotLogged(CapturedOutput output) {
        PayoutTransferRequest request = request(1L, 18_000L);
        client.send(request);
        client.send(new PayoutTransferRequest(request.payoutRequestId(), 1L, 18_000L, "004", "99999999", HOLDER));
        PayoutTransferRequest failing = request(2L, 9_000L);
        scenarios.set(failing.payoutRequestId(), MockPayoutScenario.SERVER_ERROR);
        client.send(failing);

        // 로그를 실제로 잡고 있는지 먼저 확인한다
        assertThat(output.getAll()).contains("가상 송금 처리", "가상 송금 멱등키 충돌", "SERVER_ERROR")
                .doesNotContain(ACCOUNT).doesNotContain("99999999");
        assertThat(request.toString()).doesNotContain(ACCOUNT);
    }

    // ---------------------------------------------------------------------------------------------
    // 환전 연동 계약·금융 상태 불변
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("환전 신청의 예상 수령액(20,000 − 수수료 2,000 = 18,000)으로 송금해도 환전·지갑·보류·원장·송금 이력은 바뀌지 않는다")
    void exchangeAndLedgerUnchanged() {
        Long walletId = walletRepository.save(Wallet.create(2L)).getId();
        ledgerRecordUseCase.record(new LedgerOperation("SEED:1", LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, 1L, null, Instant.parse("2026-10-01T00:00:00Z"),
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, 30_000L))));
        Long exchangeId = exchangeRequestUseCase.create(2L, "key-1", new ExchangeCommand(20_000L, "004", ACCOUNT, HOLDER));
        Map<String, Object> exchangeBefore = row("SELECT * FROM billing.exchange WHERE id = ?", exchangeId);
        Map<String, Object> walletBefore = row("SELECT * FROM billing.wallet WHERE id = ?", walletId);
        Map<String, Object> holdBefore = row("SELECT * FROM billing.point_hold WHERE id = ?",
                exchangeBefore.get("point_hold_id"));
        long ledgerBefore = count("billing.point_ledger");
        long detailBefore = count("billing.point_hold_detail");
        // 송금 요청 값은 환전 기록에서 만든다: 금액 = 예상 수령액, 계좌 = 스냅샷 복호화
        String encrypted = jdbcTemplate.queryForObject(
                "SELECT account_number_encrypted FROM billing.exchange_account WHERE exchange_id = ?", String.class,
                exchangeId);
        assertThat(exchangeAccountCipher.matches(exchangeId, encrypted, ACCOUNT)).isTrue();
        PayoutTransferRequest request = new PayoutTransferRequest(UUID.randomUUID().toString(), exchangeId,
                (Long) exchangeBefore.get("payout_amount_krw"), "004", ACCOUNT, HOLDER);

        PayoutTransferResult result = client.send(request);

        assertThat(request.amountKrw()).isEqualTo(18_000L);
        assertThat(result).isInstanceOfSatisfying(PayoutTransferResult.Succeeded.class,
                succeeded -> assertThat(succeeded.amountKrw()).isEqualTo(18_000L));
        assertThat(row("SELECT * FROM billing.exchange WHERE id = ?", exchangeId)).isEqualTo(exchangeBefore);
        assertThat(row("SELECT * FROM billing.wallet WHERE id = ?", walletId)).isEqualTo(walletBefore);
        assertThat(row("SELECT * FROM billing.point_hold WHERE id = ?", exchangeBefore.get("point_hold_id")))
                .isEqualTo(holdBefore);
        assertThat(count("billing.point_ledger")).isEqualTo(ledgerBefore);
        assertThat(count("billing.point_hold_detail")).isEqualTo(detailBefore);
        assertThat(count("billing.exchange_payout")).isZero();
        assertThat(count("billing.settlement")).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 2차 검토: 접근 키·시나리오 격리·트랜잭션 분리·재시작
    // ---------------------------------------------------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("Client와 Controller는 같은 접근 키 빈 하나를 쓰고, 키는 응답 본문·로그·toString에 나오지 않는다")
    void sameAccessKeyNeverExposed(CapturedOutput output) {
        assertThat(applicationContext.getBeansOfType(MockPayoutAccessKey.class)).hasSize(1);
        MockPayoutController controller = applicationContext.getBean(MockPayoutController.class);
        assertThat(ReflectionTestUtils.getField(controller, "accessKey")).isSameAs(accessKey);
        assertThat(ReflectionTestUtils.getField(client, "accessKey")).isSameAs(accessKey);
        String key = accessKeyValue();
        RestClient raw = RestClient.create("http://localhost:" + port);
        String id = UUID.randomUUID().toString();
        String body = "{\"payoutRequestId\":\"" + id + "\",\"exchangeId\":1,\"amountKrw\":1000,\"bankCode\":\"004\","
                + "\"accountNumber\":\"" + ACCOUNT + "\",\"accountHolderName\":\"" + HOLDER + "\"}";

        // 정상(키 맞음)·403(키 틀림)·400(ID 불일치)·409(다른 내용) 응답 본문 어디에도 키가 없다
        String responses = rawExchangeBody(raw, id, key, body) + rawExchangeBody(raw, id, "wrong-key", body)
                + rawExchangeBody(raw, id, key, body.replace(id, UUID.randomUUID().toString()))
                + rawExchangeBody(raw, id, key, body.replace("\"amountKrw\":1000", "\"amountKrw\":2000"));
        client.send(request(1L, 1_000L));

        assertThat(responses).contains("SUCCEEDED", "MOCK_PAYOUT_IDEMPOTENCY_CONFLICT").doesNotContain(key);
        assertThat(output.getAll()).contains("가상 송금 처리").doesNotContain(key);
        assertThat(accessKey.toString()).doesNotContain(key);
    }

    @Test
    @DisplayName("시나리오는 지급 요청 ID별로 독립이다: 여러 ID를 동시에 다른 시나리오로 보내도 각자 자기 결과를 받는다")
    void scenariosIsolatedUnderConcurrency() throws Exception {
        List<MockPayoutScenario> plan = List.of(MockPayoutScenario.SUCCESS, MockPayoutScenario.FAILED,
                MockPayoutScenario.DELAY, MockPayoutScenario.SERVER_ERROR, MockPayoutScenario.FAILED,
                MockPayoutScenario.SUCCESS, MockPayoutScenario.SERVER_ERROR, MockPayoutScenario.DELAY);
        List<PayoutTransferRequest> requests = new ArrayList<>();
        for (MockPayoutScenario scenario : plan) {
            PayoutTransferRequest request = request(1L, 1_000L);
            scenarios.set(request.payoutRequestId(), scenario);
            requests.add(request);
        }
        AtomicInteger next = new AtomicInteger();

        List<PayoutTransferResult> results = runConcurrently(plan.size(),
                () -> client.send(requests.get(next.getAndIncrement())));

        for (int i = 0; i < plan.size(); i++) {
            String id = requests.get(i).payoutRequestId();
            PayoutTransferResult result = results.stream()
                    .filter(candidate -> idOf(candidate).equals(id)).findFirst().orElseThrow();
            switch (plan.get(i)) {
                case SUCCESS, DELAY -> {
                    assertThat(result).isInstanceOf(PayoutTransferResult.Succeeded.class);
                    assertThat(client.find(id)).isEqualTo(result);
                }
                case FAILED -> {
                    assertThat(result).isInstanceOf(PayoutTransferResult.Failed.class);
                    assertThat(client.find(id)).isEqualTo(result);
                }
                case SERVER_ERROR -> {
                    assertThat(result).isEqualTo(new PayoutTransferResult.Unknown(id, "500:MOCK_PAYOUT_SERVER_ERROR"));
                    assertThat(client.find(id)).isEqualTo(new PayoutTransferResult.NotFound(id));
                }
                default -> throw new IllegalStateException();
            }
        }
        assertThat(transferCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("결과가 정해진 지급 요청 ID에 시나리오를 다시 지정해도 저장된 결과는 바뀌지 않고, 지정하지 않은 요청은 기본 SUCCESS다")
    void rescheduledScenarioDoesNotChangeFinalResult() {
        PayoutTransferRequest succeeded = request(1L, 1_000L);
        PayoutTransferResult first = client.send(succeeded);
        PayoutTransferRequest failed = request(1L, 1_000L);
        scenarios.set(failed.payoutRequestId(), MockPayoutScenario.FAILED);
        PayoutTransferResult firstFailure = client.send(failed);

        scenarios.set(succeeded.payoutRequestId(), MockPayoutScenario.FAILED);
        scenarios.set(failed.payoutRequestId(), MockPayoutScenario.SUCCESS);

        assertThat(first).isInstanceOf(PayoutTransferResult.Succeeded.class);
        assertThat(client.send(succeeded)).isEqualTo(first);
        assertThat(client.find(succeeded.payoutRequestId())).isEqualTo(first);
        assertThat(firstFailure).isInstanceOf(PayoutTransferResult.Failed.class);
        assertThat(client.send(failed)).isEqualTo(firstFailure);
        assertThat(client.find(failed.payoutRequestId())).isEqualTo(firstFailure);
        assertThat(transferCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("트랜잭션 안에서는 소켓 연결조차 하지 않는다. 트랜잭션 밖에서는 호출 1번에 연결 1번이고 응답이 없으면 Unknown이며 재시도하지 않는다")
    void noHttpInsideTransactionAndNoRetry() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        List<Socket> held = new CopyOnWriteArrayList<>();
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                while (!silent.isClosed()) {
                    try {
                        Socket socket = silent.accept();
                        connections.incrementAndGet();
                        held.add(socket);
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            MockPayoutHttpClient silentClient = new MockPayoutHttpClient(restClientBuilder,
                    new MockPayoutProperties(true, "http://localhost:" + silent.getLocalPort(), Duration.ofSeconds(1),
                            Duration.ofMillis(500), Duration.ZERO, Duration.ZERO, MockPayoutScenario.SUCCESS),
                    accessKey, environment);
            PayoutTransferRequest request = request(1L, 1_000L);
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> silentClient.send(request)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> transaction.executeWithoutResult(
                    status -> silentClient.find(request.payoutRequestId())))
                    .isInstanceOf(IllegalStateException.class);
            Thread.sleep(200);
            assertThat(connections.get()).isZero();

            assertThat(silentClient.send(request))
                    .isEqualTo(new PayoutTransferResult.Unknown(request.payoutRequestId(), "NO_RESPONSE"));
            assertThat(connections.get()).isEqualTo(1);
            assertThat(silentClient.find(request.payoutRequestId()))
                    .isEqualTo(new PayoutTransferResult.Unknown(request.payoutRequestId(), "NO_RESPONSE"));
            assertThat(connections.get()).isEqualTo(2);
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    @Test
    @DisplayName("Mock 처리는 접수(PROCESSING)와 결과를 각각 먼저 커밋하고, DELAY·TIMEOUT 대기 중에는 DB 트랜잭션을 열어 두지 않는다")
    void shortTransactionsAroundDelays() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // DELAY: 대기 중에 다른 연결에서 PROCESSING 접수가 보인다(이미 커밋)
            PayoutTransferRequest delayed = request(1L, 1_000L);
            scenarios.set(delayed.payoutRequestId(), MockPayoutScenario.DELAY);
            Future<PayoutTransferResult> delayedCall = executor.submit(() -> client.send(delayed));
            assertThat(awaitStatus(delayed.payoutRequestId(), "PROCESSING")).isTrue();
            assertThat(idleInTransaction()).isZero();
            assertThat(delayedCall.get(10, TimeUnit.SECONDS)).isInstanceOf(PayoutTransferResult.Succeeded.class);

            // TIMEOUT: 응답을 기다리는 동안 이미 SUCCEEDED가 커밋돼 있고 열린 트랜잭션이 없다
            PayoutTransferRequest lost = request(1L, 1_000L);
            scenarios.set(lost.payoutRequestId(), MockPayoutScenario.TIMEOUT);
            Future<PayoutTransferResult> lostCall = executor.submit(() -> client.send(lost));
            assertThat(awaitStatus(lost.payoutRequestId(), "SUCCEEDED")).isTrue();
            assertThat(lostCall.isDone()).isFalse();
            assertThat(idleInTransaction()).isZero();
            assertThat(lostCall.get(10, TimeUnit.SECONDS)).isInstanceOf(PayoutTransferResult.Unknown.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("재시작 복구: 메모리 상태 없이(새 Mock 처리 인스턴스·빈 시나리오) DB 기록만으로 같은 결과를 조회·재응답한다")
    void recoversFromDatabaseAfterRestart() {
        PayoutTransferRequest request = request(1L, 18_000L);
        PayoutTransferResult original = client.send(request);
        PayoutTransferRequest failed = request(1L, 9_000L);
        scenarios.set(failed.payoutRequestId(), MockPayoutScenario.FAILED);
        client.send(failed);

        // 재시작한 애플리케이션과 같다: 새 시나리오 저장소(비어 있음)·새 Mock 처리 인스턴스, DB는 그대로
        MockPayoutBank restarted = new MockPayoutBank(mockPayoutTransferRepository,
                new MockPayoutScenarios(mockPayoutProperties), paymentKeyCipher, transactionManager,
                mockPayoutProperties);

        MockPayoutTransferView found = restarted.find(request.payoutRequestId());
        assertThat(found.status().name()).isEqualTo("SUCCEEDED");
        assertThat(found.transactionId()).isEqualTo(((PayoutTransferResult.Succeeded) original).transactionId());
        assertThat(restarted.transfer(new MockPayoutCommand(request.payoutRequestId(), 1L, 18_000L, "004", ACCOUNT,
                HOLDER))).isEqualTo(found);
        assertThat(restarted.find(failed.payoutRequestId()).status().name()).isEqualTo("FAILED");
        assertThat(transferCount()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private static PayoutTransferRequest request(Long exchangeId, long amountKrw) {
        return new PayoutTransferRequest(UUID.randomUUID().toString(), exchangeId, amountKrw, "004", ACCOUNT, HOLDER);
    }

    private Map<String, Object> transfer(String payoutRequestId) {
        return row("SELECT * FROM billing.mock_payout_transfer WHERE payout_request_id = ?", payoutRequestId);
    }

    private int transferCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.mock_payout_transfer", Integer.class);
    }

    private Map<String, Object> row(String sql, Object id) {
        return jdbcTemplate.queryForMap(sql, id);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String accessKeyValue() {
        // 테스트는 같은 프로세스라 Client가 보내는 값을 그대로 쓴다(키 생성 방식은 바꾸지 않는다)
        return (String) ReflectionTestUtils.getField(accessKey, "value");
    }

    private static String rawExchangeBody(RestClient raw, String idempotencyKey, String key, String body) {
        return raw.post().uri("/api/v1/wallets/mock-payouts").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey).header(MockPayoutAccessKey.HEADER, key).body(body)
                .exchange((req, res) -> new String(res.getBody().readAllBytes(),
                        StandardCharsets.UTF_8));
    }

    private static String idOf(PayoutTransferResult result) {
        return switch (result) {
            case PayoutTransferResult.Succeeded r -> r.payoutRequestId();
            case PayoutTransferResult.Failed r -> r.payoutRequestId();
            case PayoutTransferResult.Processing r -> r.payoutRequestId();
            case PayoutTransferResult.NotFound r -> r.payoutRequestId();
            case PayoutTransferResult.Conflict r -> r.payoutRequestId();
            case PayoutTransferResult.Rejected r -> r.payoutRequestId();
            case PayoutTransferResult.Unknown r -> r.payoutRequestId();
        };
    }

    // 결과 대기 중 다른 연결에서 그 상태가 보일 때까지(최대 5초) 기다린다
    private boolean awaitStatus(String payoutRequestId, String status) throws InterruptedException {
        for (int i = 0; i < 250; i++) {
            List<String> found = jdbcTemplate.queryForList(
                    "SELECT status FROM billing.mock_payout_transfer WHERE payout_request_id = ?", String.class,
                    payoutRequestId);
            if (found.contains(status)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    // 이 DB에서 트랜잭션을 연 채 쉬고 있는 연결 수(이 확인 쿼리 자신은 제외)
    private int idleInTransaction() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid()
                  AND state LIKE 'idle in transaction%'""", Integer.class);
    }

    private static HttpStatusCode rawPost(RestClient raw, String idempotencyKey, String key, String body) {
        RestClient.RequestBodySpec spec = raw.post().uri("/api/v1/wallets/mock-payouts")
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", idempotencyKey);
        if (key != null) {
            spec = spec.header(MockPayoutAccessKey.HEADER, key);
        }
        return spec.body(body).exchange((req, res) -> res.getStatusCode());
    }

    private static List<PayoutTransferResult> runConcurrently(int count,
            Callable<PayoutTransferResult> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PayoutTransferResult>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<PayoutTransferResult> results = new ArrayList<>();
            for (Future<PayoutTransferResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
