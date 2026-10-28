package com.hapbang.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.billing.app.ExchangeCommand;
import com.hapbang.billing.app.ExchangeRequestUseCase;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.WalletFacade;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;

/**
 * 관리자 수동 BillingUser Backfill(#324)이 앱 설정의 shared MemberApiClient 빈(hapbang.clients.member.base-url = 자기 자신)으로
 * 실제 회원 내부 API(#322)를 HTTP로 불러, 이벤트 없이 존재하던 기존 회원의 복제본을 채우는 전체 경로를 확인한다.
 * 기존 회원은 회원 Repository로 직접 저장해 가입 이벤트를 내지 않는다(복제본 도입 전 가입자 재현).
 * 테스트 앱은 로컬 테스트 계정(MemberDataInit, 가입 이벤트 있음)도 함께 만들므로 전체 건수가 아니라 이 테스트의 회원으로 확인한다.
 * 앱 Client 주소가 server.port를 따르므로 빈 포트를 미리 골라 그 포트로 서버를 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
// 고정 포트 서버와 csrf() 세션 저장소를 다른 테스트가 이어 쓰지 않게 이 클래스가 끝나면 컨텍스트를 버린다
@DirtiesContext
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class BillingUserBackfillSelfHttpIntegrationTest {

    private static final long ADMIN = 9_900L;
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    WalletFacade walletFacade;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    ExchangeRequestUseCase exchangeRequestUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        int port = freePort();
        registry.add("server.port", () -> port);
        // 환전 신청의 계좌번호 암호화용 테스트 전용 키(실제 키 아님)
        registry.add("hapbang.billing.payment-key.encryption-key", () -> testKey(31));
        registry.add("hapbang.billing.payment-key.hmac-key", () -> testKey(47));
    }

    @Test
    @DisplayName("이벤트 없이 있던 기존 회원을 실제 회원 내부 API로 채우고, 그 뒤 관리자 환전 승인이 된다. 다시 실행하면 모두 무시된다")
    void backfillThenApprove() throws Exception {
        User user = userRepository.save(User.join("backfill-1@example.com", "기존회원1"));
        User influencer = User.join("backfill-2@example.com", "기존회원2");
        influencer.changeRole(Role.INFLUENCER);
        influencer = userRepository.save(influencer);
        long influencerId = influencer.getId();
        assertThat(replicas(user.getId(), influencerId)).isEmpty();
        long members = jdbcTemplate.queryForObject("SELECT count(*) FROM members.users", Long.class);

        // 복제본이 없어 승인할 수 없다
        Long exchangeId = exchange(influencerId);
        mockMvc.perform(post("/api/v1/settlements/admin/exchanges/{id}/approve", exchangeId).with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXCHANGE_APPLICANT_NOT_SYNCED"));

        mockMvc.perform(post("/api/v1/settlements/admin/billing-users/backfill").with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(members))
                .andExpect(jsonPath("$.data.created").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.completed").value(true))
                .andExpect(jsonPath("$.data.lastProcessedId").value(influencerId));

        assertThat(replicas(user.getId(), influencerId))
                .containsExactly(user.getId() + " USER ACTIVE 0", influencerId + " INFLUENCER ACTIVE 0");
        assertThat(replicaCount()).isEqualTo(members);
        mockMvc.perform(post("/api/v1/settlements/admin/exchanges/{id}/approve", exchangeId).with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(post("/api/v1/settlements/admin/billing-users/backfill").with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(members))
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.ignoredStale").value(members));
        assertThat(replicaCount()).isEqualTo(members);
    }

    @Test
    @DisplayName("관리자가 아니면 403이고 복제본을 만들지 않는다")
    void forbidden() throws Exception {
        User user = userRepository.save(User.join("backfill-3@example.com", "기존회원3"));

        mockMvc.perform(post("/api/v1/settlements/admin/billing-users/backfill").with(csrf())
                        .with(TestLogin.as(1L, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
        assertThat(replicas(user.getId())).isEmpty();
    }

    // 수익 HP 20,000을 만든 뒤 5,000 환전을 신청한다
    private Long exchange(long userId) {
        Long walletId = walletFacade.getOrCreateWallet(userId).getId();
        ledgerRecordUseCase.record(new LedgerOperation("SEED:backfill:" + userId, LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, userId, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, 20_000L))));
        return exchangeRequestUseCase.create(userId, "backfill-key",
                new ExchangeCommand(5_000L, "004", "12345678901234", "홍길동"));
    }

    private List<String> replicas(Long... userIds) {
        return jdbcTemplate.queryForList("SELECT id || ' ' || role || ' ' || status || ' ' || member_version "
                + "FROM billing.billing_user WHERE id = ANY(?) ORDER BY id", String.class, (Object) userIds);
    }

    private long replicaCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.billing_user", Long.class);
    }

    private static String testKey(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 13 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
