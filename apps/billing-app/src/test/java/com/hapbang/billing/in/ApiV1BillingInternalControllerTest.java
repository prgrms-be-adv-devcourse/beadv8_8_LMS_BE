package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.billing.app.HoldFacade;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.WalletReconciliationUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

// 지갑 내부 API(#273)를 실제 보안 체인·MVC 변환기·PostgreSQL로 검증한다 (docs/api/internal-billing.md, docs/billing/CONTEXT.md 1장).
// 금융 규칙 자체는 Hold*IntegrationTest가 검증하고, 여기서는 HTTP 계약(본문·상태·code·멱등 재요청·인증)과
// 같은 요청의 동시 처리, 락 대기 초과(lock_timeout 3초 → 503 HP_LOCK_TIMEOUT)를 확인한다.
// 요청 하나는 지갑 트랜잭션 하나다. 같은 JVM의 BillingPort로 호출한 쪽 트랜잭션에 참여하던 시절의 검증(#486에서 삭제)은
// 요청 단위 롤백(technicalFailureRollsBack)으로 대신한다.
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiV1BillingInternalControllerTest {

    private static final String BASE = "/api/v1/internal/billing";
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final long HOST = 1L;
    private static final long GUEST = 2L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Autowired
    HoldFacade holdFacade;

    private final AtomicInteger sequence = new AtomicInteger();

    // ---------------------------------------------------------------------------------------------
    // 성공·멱등 재요청
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("보류: 200 RsData에 참조 대상·보류액을 담고, 같은 요청은 replayed=true로 처음 결과를 돌려준다")
    void hold() throws Exception {
        Long host = wallet(HOST);
        revenue(host, 10_000L);

        call("/holds", hold(HOST, 7L, 10_000L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("HP를 보류했습니다."))
                .andExpect(jsonPath("$.data.referenceType").value("APPEARANCE_REQUEST"))
                .andExpect(jsonPath("$.data.referenceId").value(7))
                .andExpect(jsonPath("$.data.heldHp").value(10_000))
                .andExpect(jsonPath("$.data.replayed").value(false));
        call("/holds", hold(HOST, 7L, 10_000L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.heldHp").value(10_000))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertThat(holdCount()).isEqualTo(1);
        assertReconciled(host);
    }

    @Test
    @DisplayName("보류 무효화: 남은 보류를 반환해 RELEASED를 돌려주고, 같은 요청은 replayed=true이며 이후 보류는 409 HOLD_VOIDED다")
    void voidHold() throws Exception {
        Long host = wallet(HOST);
        revenue(host, 10_000L);
        call("/holds", hold(HOST, 7L, 10_000L)).andExpect(status().isOk());
        String body = """
                {"userId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 7}""";

        call("/hold-voids", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("HP 보류를 무효화했습니다."))
                .andExpect(jsonPath("$.data.referenceType").value("APPEARANCE_REQUEST"))
                .andExpect(jsonPath("$.data.referenceId").value(7))
                .andExpect(jsonPath("$.data.outcome").value("RELEASED"))
                .andExpect(jsonPath("$.data.releasedHp").value(10_000))
                .andExpect(jsonPath("$.data.replayed").value(false));
        call("/hold-voids", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("RELEASED"))
                .andExpect(jsonPath("$.data.replayed").value(true));
        call("/hold-voids", """
                {"userId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 8}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("VOIDED"))
                .andExpect(jsonPath("$.data.releasedHp").value(0));
        assertProblem(call("/holds", hold(HOST, 8L, 1_000L)), 409, "HOLD_VOIDED")
                .andExpect(jsonPath("$.detail").value("보류가 무효화된 대상이라 HP를 보류할 수 없습니다."));

        assertThat(holdCount()).isEqualTo(1);
        assertReconciled(host);
    }

    @Test
    @DisplayName("반환: amountHp를 빼면 남은 보류 전부를 반환하고, 같은 반환 요청 ID 재요청은 replayed=true다")
    void release() throws Exception {
        Long host = wallet(HOST);
        revenue(host, 10_000L);
        call("/holds", hold(HOST, 7L, 10_000L)).andExpect(status().isOk());
        String body = """
                {"userId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 7, "releaseRequestId": "CANCEL-7"}""";

        call("/releases", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("HP를 반환했습니다."))
                .andExpect(jsonPath("$.data.releaseRequestId").value("CANCEL-7"))
                .andExpect(jsonPath("$.data.releasedHp").value(10_000))
                .andExpect(jsonPath("$.data.remainingHeldHp").value(0))
                .andExpect(jsonPath("$.data.replayed").value(false));
        call("/releases", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.releasedHp").value(10_000))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertReconciled(host);
    }

    @Test
    @DisplayName("지급: 부분 지급 결과에 받는 회원·지급 원천·남은 보류액을 담고, 같은 지급 요청 ID 재요청은 replayed=true다")
    void payout() throws Exception {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        revenue(host, 10_000L);
        call("/holds", hold(HOST, 7L, 10_000L)).andExpect(status().isOk());
        String body = """
                {"payerUserId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 7, "payoutRequestId": "CONFIRM-7",
                 "receiverUserId": 2, "sourceType": "APPEARANCE_REQUEST", "sourceId": 7001, "amountHp": 4000}""";

        call("/payouts", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("HP를 지급했습니다."))
                .andExpect(jsonPath("$.data.payoutRequestId").value("CONFIRM-7"))
                .andExpect(jsonPath("$.data.receiverUserId").value(2))
                .andExpect(jsonPath("$.data.sourceType").value("APPEARANCE_REQUEST"))
                .andExpect(jsonPath("$.data.sourceId").value(7001))
                .andExpect(jsonPath("$.data.paidHp").value(4_000))
                .andExpect(jsonPath("$.data.remainingHeldHp").value(6_000))
                .andExpect(jsonPath("$.data.replayed").value(false));
        call("/payouts", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidHp").value(4_000))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertReconciled(host);
        assertReconciled(guest);
    }

    @Test
    @DisplayName("위약금 정산: 반환액·지급액을 한 번에 처리하고, 같은 취소 ID 재요청은 replayed=true다")
    void settlePenalty() throws Exception {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        revenue(host, 10_000L);
        call("/holds", hold(HOST, 7L, 10_000L)).andExpect(status().isOk());
        String body = """
                {"payerUserId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 7, "cancellationId": "HOST-CANCEL-7",
                 "receiverUserId": 2, "sourceType": "APPEARANCE_REQUEST", "sourceId": 7001,
                 "releaseAmountHp": 7000, "payoutAmountHp": 3000}""";

        call("/penalty-settlements", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("위약금을 정산했습니다."))
                .andExpect(jsonPath("$.data.cancellationId").value("HOST-CANCEL-7"))
                .andExpect(jsonPath("$.data.releasedHp").value(7_000))
                .andExpect(jsonPath("$.data.paidHp").value(3_000))
                .andExpect(jsonPath("$.data.remainingHeldHp").value(0))
                .andExpect(jsonPath("$.data.replayed").value(false));
        call("/penalty-settlements", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertReconciled(host);
        assertReconciled(guest);
    }

    // ---------------------------------------------------------------------------------------------
    // 오류 응답
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 키·다른 내용(다른 금액, 다른 회원의 같은 참조 대상)은 409 IDEMPOTENCY_KEY_CONFLICT다")
    void idempotencyConflict() throws Exception {
        revenue(wallet(HOST), 10_000L);
        revenue(wallet(GUEST), 10_000L);
        call("/holds", hold(HOST, 7L, 10_000L)).andExpect(status().isOk());

        assertProblem(call("/holds", hold(HOST, 7L, 9_000L)), 409, "IDEMPOTENCY_KEY_CONFLICT")
                .andExpect(jsonPath("$.detail").value("같은 작업 키로 다른 내용의 원장 기록이 요청되었습니다."));
        assertProblem(call("/holds", hold(GUEST, 7L, 10_000L)), 409, "IDEMPOTENCY_KEY_CONFLICT");
        assertThat(holdCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("강의는 주문 항목마다 보류하고, 한 항목을 반환해도 같은 주문의 다른 항목 보류는 그대로다")
    void lectureOrderItems() throws Exception {
        Long buyer = wallet(HOST);
        revenue(buyer, 30_000L);
        call("/holds", lectureHold(5001L, 10_000L)).andExpect(status().isOk());
        call("/holds", lectureHold(5002L, 20_000L)).andExpect(status().isOk());

        call("/releases", """
                {"userId": 1, "referenceType": "LECTURE_ORDER_ITEM", "referenceId": 5001,
                 "releaseRequestId": "ORDER-ITEM-5001-CANCEL"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referenceType").value("LECTURE_ORDER_ITEM"))
                .andExpect(jsonPath("$.data.referenceId").value(5001))
                .andExpect(jsonPath("$.data.releasedHp").value(10_000))
                .andExpect(jsonPath("$.data.remainingHeldHp").value(0));

        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_key || ' ' || (held_charge_hp + held_revenue_hp) FROM billing.point_hold ORDER BY id",
                String.class)).containsExactly("HOLD:LECTURE_ORDER_ITEM:5001 0", "HOLD:LECTURE_ORDER_ITEM:5002 20000");
        assertReconciled(buyer);
    }

    @Test
    @DisplayName("금액 오류는 400이다: 0 HP 보류·반환과 음수 지급은 INVALID_POINT_AMOUNT, 위약금 지급액 0은 PENALTY_AMOUNT_ZERO")
    void amountErrors() throws Exception {
        Long host = wallet(HOST);
        wallet(GUEST);
        revenue(host, 5_000L);
        call("/holds", hold(HOST, 7L, 5_000L)).andExpect(status().isOk());
        Map<String, Object> before = snapshot();

        assertProblem(call("/holds", hold(HOST, 8L, 0L)), 400, "INVALID_POINT_AMOUNT");
        assertProblem(call("/releases", """
                {"userId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 7, "releaseRequestId": "R1",
                 "amountHp": 0}"""), 400, "INVALID_POINT_AMOUNT");
        assertProblem(call("/payouts", """
                {"payerUserId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 7, "payoutRequestId": "P1",
                 "receiverUserId": 2, "sourceType": "APPEARANCE_REQUEST", "sourceId": 7001, "amountHp": -1}"""),
                400, "INVALID_POINT_AMOUNT");
        assertProblem(call("/penalty-settlements", """
                {"payerUserId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 7, "cancellationId": "C1",
                 "receiverUserId": 2, "sourceType": "APPEARANCE_REQUEST", "sourceId": 7001,
                 "releaseAmountHp": 5000, "payoutAmountHp": 0}"""), 400, "PENALTY_AMOUNT_ZERO");

        assertThat(snapshot()).isEqualTo(before);
        assertReconciled(host);
    }

    @Test
    @DisplayName("처리 중 기술 실패는 내부 메시지 없는 500 INTERNAL_ERROR이고, 같은 요청의 반환·지급·원장 변경이 모두 롤백된다")
    void technicalFailureRollsBack() throws Exception {
        Long host = wallet(HOST);
        wallet(GUEST);
        revenue(host, 10_000L);
        call("/holds", hold(HOST, 7L, 10_000L)).andExpect(status().isOk());
        Map<String, Object> before = snapshot();
        String body = """
                {"payerUserId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 7, "cancellationId": "HOST-CANCEL-7",
                 "receiverUserId": 2, "sourceType": "APPEARANCE_REQUEST", "sourceId": 7001,
                 "releaseAmountHp": 7000, "payoutAmountHp": 3000}""";

        // 위약금 정산의 반환 상세가 저장된 뒤 지급 상세 저장에서 실패하게 한다.
        failOnPayoutDetail(() -> assertProblem(call("/penalty-settlements", body), 500, "INTERNAL_ERROR")
                .andExpect(jsonPath("$.detail").value("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."))
                .andExpect(content().string(not(containsString("test failure")))));

        assertThat(snapshot()).isEqualTo(before);
        call("/penalty-settlements", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false));
        assertReconciled(host);
    }

    @Test
    @DisplayName("업무 오류는 BillingErrorCode의 상태와 code로 응답한다(잔액 부족 409, 지갑 없음 404, 보류 없음 404)")
    void businessErrors() throws Exception {
        revenue(wallet(HOST), 1_000L);

        assertProblem(call("/holds", hold(HOST, 7L, 10_000L)), 409, "INSUFFICIENT_BALANCE")
                .andExpect(jsonPath("$.detail").value("HP 잔액이 부족합니다."));
        assertProblem(call("/holds", hold(99L, 8L, 1_000L)), 404, "WALLET_NOT_FOUND")
                .andExpect(jsonPath("$.detail").value("지갑을 찾을 수 없습니다."));
        assertProblem(call("/releases", """
                {"userId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 9, "releaseRequestId": "R-1"}"""),
                404, "HOLD_NOT_FOUND")
                .andExpect(jsonPath("$.detail").value("HP 보류를 찾을 수 없습니다."));
        assertThat(holdCount()).isZero();
    }

    @Test
    @DisplayName("shared Command의 구조 검사 실패(필수 값 누락, 보류할 수 없는 참조 유형, 빈 요청 ID)는 400 INVALID_REQUEST다")
    void structureErrors() throws Exception {
        wallet(HOST);

        assertProblem(call("/holds", """
                {"referenceType": "APPEARANCE_REQUEST", "referenceId": 7, "amountHp": 1000}"""), 400, "INVALID_REQUEST");
        assertProblem(call("/holds", """
                {"userId": 1, "referenceType": "POINT_CHARGE", "referenceId": 7, "amountHp": 1000}"""),
                400, "INVALID_REQUEST");
        assertProblem(call("/holds", """
                {"userId": 1, "referenceType": "NOT_A_TYPE", "referenceId": 7, "amountHp": 1000}"""),
                400, "INVALID_REQUEST");
        assertProblem(call("/releases", """
                {"userId": 1, "referenceType": "APPEARANCE_REQUEST", "referenceId": 7, "releaseRequestId": ""}"""),
                400, "INVALID_REQUEST");
        assertProblem(call("/holds", "{"), 400, "INVALID_REQUEST");
        assertThat(holdCount()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 인증
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("내부 API 키가 없거나 틀리면, 로그인 세션이 있어도 본문 없는 401이고 HP가 움직이지 않는다")
    void unauthorized() throws Exception {
        revenue(wallet(HOST), 10_000L);
        String body = hold(HOST, 7L, 10_000L);

        mockMvc.perform(post(BASE + "/holds").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(post(BASE + "/holds").header(InternalApiHeaders.API_KEY, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(post(BASE + "/holds").with(TestLogin.as(HOST, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        assertThat(holdCount()).isZero();
    }

    @Test
    @DisplayName("HoldFacade는 내부 호출(ROLE_INTERNAL)만 허용한다: 인증이 없으면 거절, 회원 역할이면 403 대상")
    void facadeRequiresInternalRole() {
        revenue(wallet(HOST), 10_000L);
        HoldCommand command = new HoldCommand(HOST, BillingReferenceType.APPEARANCE_REQUEST, 7L, 10_000L);

        assertThatThrownBy(() -> holdFacade.hold(command)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        asRole("USER", () -> assertThatThrownBy(() -> holdFacade.hold(command))
                .isInstanceOf(AccessDeniedException.class));
        asRole("INTERNAL", () -> assertThat(holdFacade.hold(command).replayed()).isFalse());
        assertThat(holdCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("요청마다 요청·참조 ID와 결과(재요청 여부 또는 오류 code)를 key=value 한 줄로 남긴다")
    void requestLog(CapturedOutput output) throws Exception {
        revenue(wallet(HOST), 10_000L);

        call("/holds", hold(HOST, 7L, 10_000L)).andExpect(status().isOk());
        call("/holds", hold(HOST, 7L, 9_000L)).andExpect(status().isConflict());

        assertThat(output.getOut())
                .contains("내부 HP 요청 operation=HOLD userId=1 referenceType=APPEARANCE_REQUEST referenceId=7 amountHp=10000 "
                        + "result=OK replayed=false")
                .contains("내부 HP 요청 operation=HOLD userId=1 referenceType=APPEARANCE_REQUEST referenceId=7 amountHp=9000 "
                        + "result=FAILED code=IDEMPOTENCY_KEY_CONFLICT")
                .doesNotContain(IntegrationTest.INTERNAL_API_KEY);
    }

    // ---------------------------------------------------------------------------------------------
    // 동시성·락 대기 상한
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 회원의 같은 보류 요청 2건이 동시에 오면 지갑 락으로 직렬화되어 둘 다 200이고 하나만 replayed=true다")
    void concurrentSameHold() throws Exception {
        Long host = wallet(HOST);
        revenue(host, 10_000L);
        String body = hold(HOST, 7L, 10_000L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection lock = lockWallet(host)) {
            // 지갑 락을 잡아 두 요청이 모두 락 앞에서 기다리게 한 뒤 놓는다(lock_timeout 3초보다 짧게).
            List<CompletableFuture<MvcResult>> requests = List.of(
                    CompletableFuture.supplyAsync(() -> perform("/holds", body), executor),
                    CompletableFuture.supplyAsync(() -> perform("/holds", body), executor));
            awaitLockWaiters(2);
            lock.rollback();

            List<String> replayed = requests.stream()
                    .map(CompletableFuture::join)
                    .peek(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200))
                    .map(result -> String.valueOf((Boolean) JsonPath.read(contentOf(result), "$.data.replayed")))
                    .toList();
            assertThat(replayed).containsExactlyInAnyOrder("false", "true");
        } finally {
            executor.shutdownNow();
        }

        assertThat(holdCount()).isEqualTo(1);
        assertReconciled(host);
    }

    @Test
    @DisplayName("Billing 커넥션의 lock_timeout은 3초이고, 지갑 락을 3초 안에 얻지 못하면 503 HP_LOCK_TIMEOUT이며 HP가 움직이지 않는다")
    void lockTimeout() throws Exception {
        Long host = wallet(HOST);
        revenue(host, 10_000L);
        assertThat(jdbcTemplate.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("3s");

        long started = System.nanoTime();
        try (Connection lock = lockWallet(host)) {
            assertProblem(call("/holds", hold(HOST, 7L, 10_000L)), 503, "HP_LOCK_TIMEOUT")
                    .andExpect(jsonPath("$.detail").value("지금은 HP를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."));
            lock.rollback();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(3));

        assertThat(holdCount()).isZero();
        call("/holds", hold(HOST, 7L, 10_000L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false));
        assertReconciled(host);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private ResultActions call(String path, String body) throws Exception {
        return mockMvc.perform(post(BASE + path).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult perform(String path, String body) {
        try {
            return call(path, body).andReturn();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String contentOf(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hold(long userId, long referenceId, long amountHp) {
        return """
                {"userId": %d, "referenceType": "%s", "referenceId": %d, "amountHp": %d}"""
                .formatted(userId, BillingReferenceType.APPEARANCE_REQUEST, referenceId, amountHp);
    }

    private static String lectureHold(long referenceId, long amountHp) {
        return """
                {"userId": %d, "referenceType": "%s", "referenceId": %d, "amountHp": %d}"""
                .formatted(HOST, BillingReferenceType.LECTURE_ORDER_ITEM, referenceId, amountHp);
    }

    private ResultActions assertProblem(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    // 테스트 밖 트랜잭션에서 지갑 행을 잠그고, 커넥션을 닫거나 rollback할 때까지 유지한다.
    private Connection lockWallet(Long walletId) throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM billing.wallet WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, walletId);
            statement.executeQuery().close();
        }
        return connection;
    }

    // 지갑 락을 기다리는 다른 세션이 count개가 될 때까지 기다린다(최대 2초).
    private void awaitLockWaiters(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("지갑 락을 기다리는 요청이 " + count + "개가 되지 않았습니다.");
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

    // 지급 상세 INSERT에 실패 트리거를 잠시 걸고 동작을 실행한 뒤 지운다(반환 상세는 저장된 뒤 지급 상세에서 실패한다).
    private void failOnPayoutDetail(ThrowingRunnable action) throws Exception {
        jdbcTemplate.execute("""
                CREATE FUNCTION billing.test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test failure';
                END;
                $$""");
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_test_fail BEFORE INSERT ON billing.point_hold_detail FOR EACH ROW
                WHEN (NEW.action_type = 'PAYOUT') EXECUTE FUNCTION billing.test_fail()""");
        try {
            action.run();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER trg_test_fail ON billing.point_hold_detail");
            jdbcTemplate.execute("DROP FUNCTION billing.test_fail()");
        }
    }

    // 이 스레드에서만 주어진 역할로 인증된 상태로 실행한다.
    private static void asRole(String role, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // 지갑·보류·상세·지급 건·원장 상태를 비교할 수 있게 모은다.
    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("wallets", jdbcTemplate.queryForList("""
                SELECT id, charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet ORDER BY id"""));
        snapshot.put("holds", jdbcTemplate.queryForList(
                "SELECT id, held_charge_hp, held_revenue_hp, status FROM billing.point_hold ORDER BY id"));
        snapshot.put("details", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key FROM billing.point_hold_detail ORDER BY id"));
        snapshot.put("settlements", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key FROM billing.settlement ORDER BY id"));
        snapshot.put("ledgers", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key, amount_hp FROM billing.point_ledger ORDER BY id"));
        return snapshot;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private int holdCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold", Integer.class);
    }

    private void assertReconciled(Long walletId) {
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }
}
