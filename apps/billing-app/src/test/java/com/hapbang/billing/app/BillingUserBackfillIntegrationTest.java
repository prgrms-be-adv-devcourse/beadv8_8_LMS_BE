package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingUser;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.out.BillingUserRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;
import com.sun.net.httpserver.HttpServer;

// 기존 회원 BillingUser Backfill(#324)을 실제 PostgreSQL과 실제 shared MemberApiClient로 검증한다. 회원 내부 API는 가짜 HTTP 서버다.
// 회원 이벤트와 같은 반영 규칙(생성·높은 버전만 갱신·같거나 낮은 버전·버전 없음 무시), 다시 실행해도 같은 결과,
// 이벤트와의 경쟁(옛 Backfill 응답이 최신 이벤트를 덮지 않음, 회원당 한 행), 관리자 API(권한·RsData·ProblemDetail·검증),
// Backfill 뒤 관리자 환전 승인, 지갑·원장을 건드리지 않음을 본다.
// 가짜 서버 주소 때문에 이 클래스만 쓰는 컨텍스트라 연결 풀을 작게 두고 끝나면 닫는다.
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingUserBackfillIntegrationTest {

    private static final String URL = "/api/v1/settlements/admin/billing-users/backfill";
    private static final long ADMIN = 900L;
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");

    private static final HttpServer SERVER = startServer();
    // 가짜 회원 원본: 회원 ID → 회원 정보. 키셋 페이지로 응답한다
    private static final ConcurrentSkipListMap<Long, UserDto> MEMBERS = new ConcurrentSkipListMap<>();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    // 응답 직전에 실행할 일(조회 뒤·저장 전 회원 이벤트 반영 재현)과 강제 응답 상태
    private static volatile Runnable beforeRespond;
    private static volatile int forcedStatus;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.clients.member.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("hapbang.billing.payment-key.encryption-key", () -> ChargeFixture.ENCRYPTION_KEY);
        registry.add("hapbang.billing.payment-key.hmac-key", () -> ChargeFixture.HMAC_KEY);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired
    BillingUserBackfillUseCase backfillUseCase;

    @Autowired
    BillingUserSyncUseCase syncUseCase;

    @Autowired
    BillingUserRepository billingUserRepository;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    ExchangeRequestUseCase exchangeRequestUseCase;

    @Autowired
    ExchangeReviewUseCase exchangeReviewUseCase;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void reset() {
        MEMBERS.clear();
        REQUESTS.clear();
        beforeRespond = null;
        forcedStatus = 0;
    }

    // ---------------------------------------------------------------------------------------------
    // 반영 규칙
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("복제본이 없는 기존 회원(모든 역할·상태)을 만들고, 지갑·원장은 만들지 않는다")
    void createsMissing() {
        member(1L, Role.USER, UserStatus.ACTIVE, 0L);
        member(2L, Role.INFLUENCER, UserStatus.SUSPENDED, 3L);
        member(3L, Role.ADMIN, UserStatus.ACTIVE, 1L);
        member(4L, Role.VERIFIED_INFLUENCER, UserStatus.WITHDRAWN, 7L);

        BillingUserBackfillResult result = backfillUseCase.run(0, 5_000);

        assertThat(result.created()).isEqualTo(4);
        assertThat(result.completed()).isTrue();
        assertThat(replicas()).containsExactly("1 member1 USER ACTIVE 0", "2 member2 INFLUENCER SUSPENDED 3",
                "3 member3 ADMIN ACTIVE 1", "4 member4 VERIFIED_INFLUENCER WITHDRAWN 7");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.wallet WHERE wallet_type = 'MEMBER'",
                Long.class)).isZero();
        assertThat(count("billing.point_ledger")).isZero();
    }

    @Test
    @DisplayName("더 높은 버전은 갱신하고, 같은 버전·낮은 버전·버전 없는 정보는 무시해 기존 복제본을 그대로 둔다")
    void versionRules() {
        syncUseCase.apply(new UserDto(1L, "old", Role.USER, UserStatus.ACTIVE, 1L));
        syncUseCase.apply(new UserDto(2L, "same", Role.INFLUENCER, UserStatus.ACTIVE, 5L));
        syncUseCase.apply(new UserDto(3L, "newer", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 9L));
        syncUseCase.apply(new UserDto(4L, "versioned", Role.INFLUENCER, UserStatus.ACTIVE, 2L));
        member(1L, Role.INFLUENCER, UserStatus.ACTIVE, 2L);
        member(2L, Role.USER, UserStatus.SUSPENDED, 5L);
        member(3L, Role.USER, UserStatus.ACTIVE, 4L);
        MEMBERS.put(4L, new UserDto(4L, "member4", Role.USER, UserStatus.ACTIVE, null));
        member(5L, Role.USER, UserStatus.ACTIVE, null);

        BillingUserBackfillResult result = backfillUseCase.run(0, 5_000);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.ignoredStale()).isEqualTo(2);
        assertThat(result.ignoredVersionless()).isEqualTo(2);
        assertThat(result.scanned()).isEqualTo(5);
        assertThat(replicas()).containsExactly("1 member1 INFLUENCER ACTIVE 2", "2 same INFLUENCER ACTIVE 5",
                "3 newer VERIFIED_INFLUENCER ACTIVE 9", "4 versioned INFLUENCER ACTIVE 2");
    }

    @Test
    @DisplayName("다시 실행해도 회원당 한 행이고 결과가 같다(두 번째는 모두 무시), 이어서 실행해도 중복이 없다")
    void rerunIdempotent() {
        for (long id = 1; id <= 1_200; id++) {
            member(id, Role.USER, UserStatus.ACTIVE, 1L);
        }

        BillingUserBackfillResult first = backfillUseCase.run(0, 700);
        BillingUserBackfillResult rest = backfillUseCase.run(first.nextAfterId(), 5_000);
        BillingUserBackfillResult again = backfillUseCase.run(0, 5_000);

        assertThat(first.created()).isEqualTo(700);
        assertThat(first.nextAfterId()).isEqualTo(700L);
        assertThat(rest.created()).isEqualTo(500);
        assertThat(rest.completed()).isTrue();
        assertThat(again.created()).isZero();
        assertThat(again.ignoredStale()).isEqualTo(1_200);
        assertThat(count("billing.billing_user")).isEqualTo(1_200);
        assertThat(jdbcTemplate.queryForObject("SELECT count(DISTINCT id) FROM billing.billing_user", Long.class))
                .isEqualTo(1_200L);
    }

    @Test
    @DisplayName("Backfill이 v1을 읽은 뒤 회원 변경 이벤트 v2(탈퇴)가 먼저 반영되면, 늦게 저장하려던 v1은 무시되고 v2가 남는다")
    void staleBackfillDoesNotOverwriteEvent() {
        member(1L, Role.INFLUENCER, UserStatus.ACTIVE, 1L);
        beforeRespond = () -> syncUseCase.apply(new UserDto(1L, "withdrawn", Role.INFLUENCER, UserStatus.WITHDRAWN, 2L));

        BillingUserBackfillResult result = backfillUseCase.run(0, 5_000);

        assertThat(result.ignoredStale()).isEqualTo(1);
        assertThat(result.created()).isZero();
        assertThat(replicas()).containsExactly("1 withdrawn INFLUENCER WITHDRAWN 2");
    }

    @Test
    @DisplayName("Backfill(v1)과 회원 이벤트(v2)가 같은 회원들을 동시에 반영해도 회원당 한 행이고 모두 v2가 남는다")
    void concurrentWithEvents() throws Exception {
        int members = 60;
        for (long id = 1; id <= members; id++) {
            member(id, Role.USER, UserStatus.ACTIVE, 1L);
        }
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BillingUserBackfillResult> backfill = executor.submit(() -> {
                start.await();
                return backfillUseCase.run(0, 5_000);
            });
            List<Future<?>> events = new ArrayList<>();
            for (int worker = 0; worker < 3; worker++) {
                int offset = worker;
                events.add(executor.submit(() -> {
                    start.await();
                    for (long id = members - offset; id >= 1; id -= 3) {
                        syncUseCase.apply(new UserDto(id, "event" + id, Role.INFLUENCER, UserStatus.ACTIVE, 2L));
                    }
                    return null;
                }));
            }
            start.countDown();
            BillingUserBackfillResult result = backfill.get(60, TimeUnit.SECONDS);
            for (Future<?> event : events) {
                event.get(60, TimeUnit.SECONDS);
            }
            assertThat(result.failed()).isZero();
            assertThat(result.scanned()).isEqualTo(members);
            assertThat(result.scanned()).isEqualTo(result.created() + result.updated() + result.ignoredStale()
                    + result.ignoredVersionless() + result.failed());
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("billing.billing_user")).isEqualTo(members);
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT member_version || ' ' || role FROM billing.billing_user",
                String.class)).containsExactly("2 INFLUENCER");
    }

    @Test
    @DisplayName("회원 내부 API 5xx로 멈추면 이미 반영한 회원은 유지되고, 처음부터 다시 실행하면 나머지를 채운다")
    void stopAndRerun() {
        for (long id = 1; id <= 700; id++) {
            member(id, Role.USER, UserStatus.ACTIVE, 1L);
        }
        AtomicInteger calls = new AtomicInteger();
        beforeRespond = () -> {
            if (calls.incrementAndGet() == 2) {
                forcedStatus = 503;
            }
        };

        assertThatThrownBy(() -> backfillUseCase.run(0, 5_000))
                .isInstanceOfSatisfying(BillingUserBackfillException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.MEMBER_SOURCE_UNAVAILABLE);
                    assertThat(e.getProgress().created()).isEqualTo(500);
                    assertThat(e.getProgress().nextAfterId()).isEqualTo(500L);
                });
        assertThat(count("billing.billing_user")).isEqualTo(500);

        beforeRespond = null;
        forcedStatus = 0;
        BillingUserBackfillResult rerun = backfillUseCase.run(0, 5_000);
        assertThat(rerun.ignoredStale()).isEqualTo(500);
        assertThat(rerun.created()).isEqualTo(200);
        assertThat(count("billing.billing_user")).isEqualTo(700);
    }

    @Test
    @DisplayName("복제본이 없어 승인할 수 없던(409 EXCHANGE_APPLICANT_NOT_SYNCED) 환전이 Backfill 뒤에는 승인된다")
    void exchangeApprovalAfterBackfill() {
        long influencer = 2L;
        Long walletId = walletRepository.save(Wallet.create(influencer)).getId();
        ledgerRecordUseCase.record(new LedgerOperation("SEED:" + sequence.incrementAndGet(),
                LedgerTransactionType.PAYOUT, BillingReferenceType.SETTLEMENT, (long) sequence.get(), null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, 20_000L))));
        Long exchangeId = exchangeRequestUseCase.create(influencer, "key-1",
                new ExchangeCommand(5_000L, "004", "12345678901234", "홍길동"));
        assertThatThrownBy(() -> exchangeReviewUseCase.approve(ADMIN, exchangeId))
                .isInstanceOfSatisfying(BillingException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.EXCHANGE_APPLICANT_NOT_SYNCED));
        member(influencer, Role.INFLUENCER, UserStatus.ACTIVE, 3L);
        long ledgerBefore = count("billing.point_ledger");

        backfillUseCase.run(0, 5_000);

        assertThat(count("billing.point_ledger")).isEqualTo(ledgerBefore);
        assertThat(exchangeReviewUseCase.approve(ADMIN, exchangeId).status()).isEqualTo(ExchangeStatus.APPROVED);
    }

    // ---------------------------------------------------------------------------------------------
    // 관리자 API
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("관리자: 200 RsData에 집계·커서를 담고 회원 닉네임은 응답에 없다. 기본 afterId 0, limit 100")
    void api_admin() throws Exception {
        member(1L, Role.USER, UserStatus.ACTIVE, 0L);
        member(2L, Role.INFLUENCER, UserStatus.ACTIVE, 1L);

        mockMvc.perform(post(URL).with(csrf()).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("회원 정보 Backfill을 실행했습니다."))
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data.scanned").value(2))
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.updated").value(0))
                .andExpect(jsonPath("$.data.ignoredStale").value(0))
                .andExpect(jsonPath("$.data.ignoredVersionless").value(0))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.failedIds.length()").value(0))
                .andExpect(jsonPath("$.data.lastProcessedId").value(2))
                .andExpect(jsonPath("$.data.completed").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("member1"))));
        assertThat(REQUESTS).containsExactly("afterId=0&size=100");

        REQUESTS.clear();
        mockMvc.perform(post(URL).param("afterId", "1").param("limit", "1").with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(1))
                .andExpect(jsonPath("$.data.completed").value(true));
        assertThat(REQUESTS).containsExactly("afterId=1&size=1");
    }

    @Test
    @DisplayName("limit을 생략하면 100명만 처리하고 nextAfterId로 이어서 실행하며, 마지막 실행은 completed다")
    void api_defaultLimit() throws Exception {
        for (long id = 1; id <= 250; id++) {
            member(id, Role.USER, UserStatus.ACTIVE, 1L);
        }

        mockMvc.perform(post(URL).with(csrf()).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(100))
                .andExpect(jsonPath("$.data.created").value(100))
                .andExpect(jsonPath("$.data.lastProcessedId").value(100))
                .andExpect(jsonPath("$.data.nextAfterId").value(100))
                .andExpect(jsonPath("$.data.completed").value(false));
        assertThat(REQUESTS).containsExactly("afterId=0&size=100");
        assertThat(count("billing.billing_user")).isEqualTo(100);

        mockMvc.perform(post(URL).param("afterId", "100").with(csrf()).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(100))
                .andExpect(jsonPath("$.data.nextAfterId").value(200))
                .andExpect(jsonPath("$.data.completed").value(false));
        mockMvc.perform(post(URL).param("afterId", "200").with(csrf()).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(50))
                .andExpect(jsonPath("$.data.lastProcessedId").value(250))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist())
                .andExpect(jsonPath("$.data.completed").value(true));
        assertThat(REQUESTS).containsExactly("afterId=0&size=100", "afterId=100&size=100", "afterId=200&size=100");
        assertThat(count("billing.billing_user")).isEqualTo(250);
    }

    @Test
    @DisplayName("limit=5000을 명시하면 500명씩 10번 읽어 최대 5,000명까지 처리하고, 남은 회원이 있으면 nextAfterId = 5000이다")
    void api_explicitMaxLimit() throws Exception {
        for (long id = 1; id <= 5_100; id++) {
            member(id, Role.USER, UserStatus.ACTIVE, 1L);
        }

        mockMvc.perform(post(URL).param("limit", "5000").with(csrf()).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(5_000))
                .andExpect(jsonPath("$.data.created").value(5_000))
                .andExpect(jsonPath("$.data.lastProcessedId").value(5_000))
                .andExpect(jsonPath("$.data.nextAfterId").value(5_000))
                .andExpect(jsonPath("$.data.completed").value(false));
        assertThat(REQUESTS).hasSize(10).allSatisfy(request -> assertThat(request).endsWith("&size=500"));
        assertThat(count("billing.billing_user")).isEqualTo(5_000);

        REQUESTS.clear();
        mockMvc.perform(post(URL).param("afterId", "5000").param("limit", "5000").with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(100))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist())
                .andExpect(jsonPath("$.data.completed").value(true));
        assertThat(REQUESTS).containsExactly("afterId=5000&size=500");
        assertThat(count("billing.billing_user")).isEqualTo(5_100);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "INFLUENCER", "VERIFIED_INFLUENCER"})
    @DisplayName("관리자가 아니면 403, 회원 내부 API를 부르지 않는다")
    void api_forbidden(Role role) throws Exception {
        mockMvc.perform(post(URL).with(csrf()).with(TestLogin.as(1L, role)))
                .andExpect(status().isForbidden());
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    @DisplayName("비로그인은 401이고 회원 내부 API를 부르지 않는다")
    void api_unauthenticated() throws Exception {
        mockMvc.perform(post(URL).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"afterId, -1", "limit, 0", "limit, 5001", "limit, x", "afterId, y"})
    @DisplayName("afterId < 0, limit 1~5,000 밖, 숫자가 아닌 값은 400 INVALID_REQUEST(ProblemDetail)이다")
    void api_invalid(String name, String value) throws Exception {
        mockMvc.perform(post(URL).param(name, value).with(csrf()).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value(name));
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    @DisplayName("회원 내부 API가 거절(401)하면 503 ProblemDetail MEMBER_SOURCE_REJECTED에 집계·resumeAfterId를 담는다")
    void api_sourceFailure() throws Exception {
        member(1L, Role.USER, UserStatus.ACTIVE, 0L);
        forcedStatus = 401;

        mockMvc.perform(post(URL).param("afterId", "0").with(csrf()).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("MEMBER_SOURCE_REJECTED"))
                .andExpect(jsonPath("$.scanned").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.resumeAfterId").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(count("billing.billing_user")).isZero();
    }

    // ---------------------------------------------------------------------------------------------

    private static void member(long id, Role role, UserStatus status, Long version) {
        MEMBERS.put(id, new UserDto(id, "member" + id, role, status, version));
    }

    private List<String> replicas() {
        List<String> rows = new ArrayList<>();
        for (BillingUser user : jdbcTemplate.queryForList("SELECT id FROM billing.billing_user ORDER BY id", Long.class)
                .stream().map(id -> billingUserRepository.findById(id).orElseThrow()).toList()) {
            rows.add(user.getId() + " " + user.getNickname() + " " + user.getRole() + " " + user.getStatus() + " "
                    + user.getMemberVersion());
        }
        return rows;
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static String json(UserDto user) {
        return "{\"id\":" + user.id() + ",\"nickname\":\"" + user.nickname() + "\",\"role\":\"" + user.role()
                + "\",\"status\":\"" + user.status() + "\"" + (user.version() == null ? "" : ",\"version\":"
                + user.version()) + "}";
    }

    // 실제 회원 내부 API처럼 afterId보다 큰 ID를 오름차순으로 size개 주고, 더 있으면 마지막 ID를 nextAfterId로 준다
    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/internal/members", exchange -> {
                Map<String, String> query = new HashMap<>();
                String raw = exchange.getRequestURI().getRawQuery();
                for (String pair : raw == null ? new String[0] : raw.split("&")) {
                    String[] kv = pair.split("=", 2);
                    query.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
                REQUESTS.add("afterId=" + query.get("afterId") + "&size=" + query.get("size"));
                Runnable hook = beforeRespond;
                if (hook != null) {
                    hook.run();
                }
                String body = null;
                int status = forcedStatus == 0 ? 200 : forcedStatus;
                if (status == 200) {
                    long afterId = query.get("afterId") == null ? 0 : Long.parseLong(query.get("afterId"));
                    int size = Integer.parseInt(query.get("size"));
                    List<UserDto> rest = new ArrayList<>(MEMBERS.tailMap(afterId, false).values());
                    List<UserDto> page = rest.subList(0, Math.min(size, rest.size()));
                    StringBuilder items = new StringBuilder();
                    for (UserDto user : page) {
                        items.append(items.isEmpty() ? "" : ",").append(json(user));
                    }
                    body = "{\"statusCode\":200,\"message\":\"ok\",\"data\":{\"items\":[" + items + "]"
                            + (rest.size() > size ? ",\"nextAfterId\":" + page.getLast().id() : "") + "}}";
                }
                byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
