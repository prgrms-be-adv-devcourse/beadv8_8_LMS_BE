package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.hapbang.billing.app.HoldFacade;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// 내 HP 거래 내역 API(#295)의 RsData·페이지 응답 모양, 시각(Instant UTC), 로그인 회원 기준 조회, 인가, page·size 검증을 MockMvc로 확인한다.
// 원장 그룹화·분류 규칙은 WalletTransactionQueryIntegrationTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1WalletTransactionControllerTest {

    private static final String URL = "/api/v1/wallets/me/transactions";
    private static final Instant T1 = Instant.parse("2026-10-01T03:04:05.123456Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    HoldFacade holdFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("200 RsData의 data가 {content, page}이고, 거래마다 유형·표시명·참조·UTC 시각·가용·보류·총액·종류별 증감을 담는다")
    void response() throws Exception {
        Long wallet = walletRepository.save(Wallet.create(1L)).getId();
        chargeWithPrincipal(wallet, 5_000L);
        // HoldFacade는 내부 호출(ROLE_INTERNAL)만 허용한다(D63, #273). 요청에 섞이지 않게 이 호출에만 인증을 둔다
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("internal", null,
                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
        try {
            holdFacade.hold(new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 11L, 2_000L));
        } finally {
            SecurityContextHolder.clearContext();
        }

        mockMvc.perform(get(URL).with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("HP 거래 내역을 조회했습니다."))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.page.totalPages").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].transactionId").isNumber())
                .andExpect(jsonPath("$.data.content[0].type").value("PAYMENT_HOLD"))
                .andExpect(jsonPath("$.data.content[0].displayName").value("결제 보류"))
                .andExpect(jsonPath("$.data.content[0].referenceType").value("LECTURE_ORDER_ITEM"))
                .andExpect(jsonPath("$.data.content[0].referenceId").value(11))
                .andExpect(jsonPath("$.data.content[0].availableChangeHp").value(-2000))
                .andExpect(jsonPath("$.data.content[0].heldChangeHp").value(2000))
                .andExpect(jsonPath("$.data.content[0].totalChangeHp").value(0))
                .andExpect(jsonPath("$.data.content[0].charge.availableChangeHp").value(-2000))
                .andExpect(jsonPath("$.data.content[0].charge.heldChangeHp").value(2000))
                .andExpect(jsonPath("$.data.content[0].charge.totalChangeHp").value(0))
                .andExpect(jsonPath("$.data.content[0].revenue.availableChangeHp").value(0))
                .andExpect(jsonPath("$.data.content[0].revenue.heldChangeHp").value(0))
                .andExpect(jsonPath("$.data.content[0].revenue.totalChangeHp").value(0))
                .andExpect(jsonPath("$.data.content[1].type").value("CHARGE"))
                .andExpect(jsonPath("$.data.content[1].displayName").value("HP 충전"))
                .andExpect(jsonPath("$.data.content[1].occurredAt").value("2026-10-01T03:04:05.123456Z"))
                .andExpect(jsonPath("$.data.content[1].availableChangeHp").value(5000))
                .andExpect(jsonPath("$.data.content[1].totalChangeHp").value(5000))
                // 내부 작업 키는 내보내지 않는다
                .andExpect(jsonPath("$.data.content[0].operationKey").doesNotExist());
    }

    @Test
    @DisplayName("page·size로 작업 단위 페이지를 나누고, 요청의 sort는 무시하며 범위 밖 페이지는 빈 목록이다")
    void paging() throws Exception {
        Long wallet = walletRepository.save(Wallet.create(1L)).getId();
        seedCharge(wallet, 1_000L);
        seedCharge(wallet, 2_000L);
        seedCharge(wallet, 3_000L);

        mockMvc.perform(get(URL).param("page", "1").param("size", "2").param("sort", "id,asc")
                        .with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.size").value(2))
                .andExpect(jsonPath("$.data.page.number").value(1))
                .andExpect(jsonPath("$.data.page.totalElements").value(3))
                .andExpect(jsonPath("$.data.page.totalPages").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].availableChangeHp").value(1000));
        mockMvc.perform(get(URL).param("page", "9").param("size", "100").with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(3));
    }

    @ParameterizedTest
    @CsvSource({"page, -1", "size, 0", "size, 101", "page, abc", "size, x"})
    @DisplayName("page < 0, size < 1, size > 100, 숫자가 아닌 값은 400 INVALID_REQUEST(ProblemDetail)이다")
    void invalidPaging(String name, String value) throws Exception {
        mockMvc.perform(get(URL).param(name, value).with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value(name));
    }

    @Test
    @DisplayName("로그인한 회원 자신의 거래만 보이고, userId를 넘겨도 다른 회원 거래를 볼 수 없다")
    void ownOnly() throws Exception {
        Long other = walletRepository.save(Wallet.create(2L)).getId();
        seedCharge(other, 9_000L);
        Long mine = walletRepository.save(Wallet.create(1L)).getId();
        seedCharge(mine, 1_000L);

        mockMvc.perform(get(URL).param("userId", "2").with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].availableChangeHp").value(1000));
    }

    @Test
    @DisplayName("지갑이 없는 회원은 200 빈 목록이고 지갑을 만들지 않는다")
    void noWallet() throws Exception {
        mockMvc.perform(get(URL).with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));

        assertThat(walletRepository.findByUserId(1L)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"INFLUENCER", "VERIFIED_INFLUENCER"})
    @DisplayName("인플루언서·인증된 인플루언서도 조회할 수 있다")
    void influencers(Role role) throws Exception {
        mockMvc.perform(get(URL).with(TestLogin.as(1L, role)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비로그인 401, 관리자 403(ProblemDetail FORBIDDEN)")
    void unauthorized() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(get(URL).with(TestLogin.as(1L, Role.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private int seq;

    // 보류가 충전 원금을 배분할 수 있게 완료된 충전 건과 충전 원장을 함께 남긴다.
    private void chargeWithPrincipal(Long walletId, long amountHp) {
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', 'order-1', 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, Timestamp.from(T1), Timestamp.from(T1));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
    }

    private void seedCharge(Long walletId, long amountHp) {
        seq++;
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + (900 + seq), LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, 900L + seq, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
    }
}
