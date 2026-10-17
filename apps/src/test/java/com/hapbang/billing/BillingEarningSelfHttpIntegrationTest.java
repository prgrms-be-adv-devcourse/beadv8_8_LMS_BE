package com.hapbang.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.WalletFacade;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

/**
 * Billing 지급 예정 수익 통합 API(#295)가 앱 설정의 shared Client 빈(hapbang.clients.*.base-url = 자기 자신)으로 실제 강의·출연
 * 내부 API를 HTTP로 불러 합치는 전체 경로를 확인한다. 강의 판매(주문 항목 2건)와 출연(요청 수락 1건)을 실제로 만든다.
 * 앱 Client 주소가 server.port를 따르므로 빈 포트를 미리 골라 그 포트로 서버를 띄운다(RANDOM_PORT는 Client 주소가 0번 포트가 된다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
// 고정 포트 서버와 csrf() 세션 저장소를 다른 테스트가 이어 쓰지 않게 이 클래스가 끝나면 컨텍스트를 버린다
@DirtiesContext
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class BillingEarningSelfHttpIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long HOST = 9_501L;
    private static final long INFLUENCER = 9_502L;
    private static final long BUYER = 9_503L;
    private static final long PLAIN_USER = 9_504L;
    private static final long FEE = 30_000L;
    private static final long PRICE_HP = 10_000L;
    private static final Instant FUNDED_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    WalletFacade walletFacade;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    LectureRepository lectureRepository;

    @Autowired
    LectureOrderRepository lectureOrderRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @DynamicPropertySource
    static void port(DynamicPropertyRegistry registry) {
        int port = freePort();
        registry.add("server.port", () -> port);
    }

    @BeforeEach
    void setUp() {
        for (long id : List.of(HOST, INFLUENCER)) {
            appearanceUserFacade.syncJoined(new UserDto(id, "user" + id, Role.INFLUENCER, UserStatus.ACTIVE));
        }
    }

    @Test
    @DisplayName("강의 판매 2건 + 출연 1건: 요약은 상태별로 합치고, 목록은 두 출처를 상태 변경 최신순으로 합친 정확한 전역 페이지다")
    void summaryAndList() throws Exception {
        long appearanceId = acceptedAppearance();
        LectureOrder order = lectureOrderRepository.save(LectureOrder.place(BUYER,
                List.of(saveLecture("첫 강의"), saveLecture("둘째 강의")), Set.of(), LocalDateTime.now(LectureConfig.ZONE)));

        mockMvc.perform(get("/api/v1/settlements/earnings/summary").with(TestLogin.as(INFLUENCER, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationPending.amountHp").value(FEE + PRICE_HP * 2))
                .andExpect(jsonPath("$.data.confirmationPending.count").value(3))
                .andExpect(jsonPath("$.data.scheduled.amountHp").value(0))
                .andExpect(jsonPath("$.data.total.amountHp").value(FEE + PRICE_HP * 2))
                .andExpect(jsonPath("$.data.total.count").value(3));

        String body = mockMvc.perform(get("/api/v1/settlements/earnings").with(TestLogin.as(INFLUENCER,
                        Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(3))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> items = JsonPath.read(body, "$.data.content");
        assertThat(items).extracting(item -> item.get("sourceType") + ":" + item.get("sourceId"))
                .containsExactlyInAnyOrder("APPEARANCE:" + appearanceId,
                        "LECTURE:" + order.getItems().get(0).getId(), "LECTURE:" + order.getItems().get(1).getId());
        List<Instant> times = items.stream().map(item -> Instant.parse((String) item.get("statusUpdatedAt"))).toList();
        assertThat(times).isSortedAccordingTo((a, b) -> b.compareTo(a));
        // 출연 시각(KST LocalDateTime)을 Asia/Seoul로 해석한 Instant다
        LocalDateTime appearanceUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM appearances.appearances WHERE id = ?", LocalDateTime.class, appearanceId);
        Map<String, Object> appearance = items.stream()
                .filter(item -> "APPEARANCE".equals(item.get("sourceType"))).findFirst().orElseThrow();
        assertThat(Instant.parse((String) appearance.get("statusUpdatedAt")))
                .isEqualTo(appearanceUpdatedAt.atZone(KST).toInstant());
        assertThat(appearance.get("earningType")).isEqualTo("APPEARANCE_FEE");
        assertThat(appearance.get("payoutScheduledAt")).isNull();

        // size 1로 페이지를 넘기며 모으면 한 번에 받은 순서와 같다
        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String pageBody = mockMvc.perform(get("/api/v1/settlements/earnings").param("page", String.valueOf(page))
                            .param("size", "1").with(TestLogin.as(INFLUENCER, Role.INFLUENCER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.page.totalPages").value(3))
                    .andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> content = JsonPath.read(pageBody, "$.data.content");
            content.forEach(item -> paged.add(item.get("sourceType") + ":" + item.get("sourceId")));
        }
        assertThat(paged).containsExactlyElementsOf(
                items.stream().map(item -> item.get("sourceType") + ":" + item.get("sourceId")).toList());
    }

    @Test
    @DisplayName("수익이 없는 일반 유저는 실제 두 내부 API를 거쳐 0원 요약·빈 목록을 받는다")
    void plainUserZero() throws Exception {
        mockMvc.perform(get("/api/v1/settlements/earnings/summary").with(TestLogin.as(PLAIN_USER, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total.amountHp").value(0))
                .andExpect(jsonPath("$.data.total.count").value(0))
                .andExpect(jsonPath("$.data.asOf").exists());
        mockMvc.perform(get("/api/v1/settlements/earnings").with(TestLogin.as(PLAIN_USER, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
    }

    @Test
    @DisplayName("출연 요청을 보낸 호스트의 HP 거래 내역에 충전과 결제 보류가 별도 거래로 나온다")
    void hostTransactions() throws Exception {
        acceptedAppearance();

        mockMvc.perform(get("/api/v1/wallets/me/transactions").with(TestLogin.as(HOST, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].type").value("PAYMENT_HOLD"))
                .andExpect(jsonPath("$.data.content[0].referenceType").value("APPEARANCE_REQUEST"))
                .andExpect(jsonPath("$.data.content[0].availableChangeHp").value(-FEE))
                .andExpect(jsonPath("$.data.content[0].heldChangeHp").value(FEE))
                .andExpect(jsonPath("$.data.content[0].totalChangeHp").value(0))
                .andExpect(jsonPath("$.data.content[1].type").value("CHARGE"))
                .andExpect(jsonPath("$.data.content[1].availableChangeHp").value(FEE));
    }

    // ---------------------------------------------------------------------------------------------

    /** 호스트가 인플루언서에게 30,000 출연 요청을 보내고 인플루언서가 수락한다. 성립한 출연 ID */
    private long acceptedAppearance() throws Exception {
        fund(HOST, FEE);
        long requestId = idOf(perform(HOST, "/api/v1/appearances/requests", requestBody())
                .andExpect(status().isCreated()), "$.data.id");
        long version = jdbcTemplate.queryForObject("SELECT version FROM appearances.requests WHERE id = ?", Long.class,
                requestId);
        return idOf(perform(INFLUENCER, "/api/v1/appearances/requests/" + requestId + "/accept",
                "{\"version\": " + version + "}").andExpect(status().isOk()), "$.data.appearanceId");
    }

    private ResultActions perform(long userId, String path, String body) throws Exception {
        return mockMvc.perform(post(path).with(TestLogin.as(userId, Role.INFLUENCER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long idOf(ResultActions result, String path) throws Exception {
        String response = result.andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, path)).longValue();
    }

    private String requestBody() {
        LocalDateTime now = LocalDateTime.now(clock);
        return """
                {"guestId": %d, "title": "주말 게임 합방 출연 요청", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 신작 게임을 플레이합니다.",
                 "fee": %d, "acceptDeadline": "%s"}
                """.formatted(INFLUENCER, now.plusDays(7).withNano(0), FEE, now.plusDays(2).withNano(0));
    }

    /** 충전 완료 건 + 같은 금액의 충전 원장으로 충전 HP를 만든다 (Toss 결제를 거치지 않는다) */
    private void fund(long userId, long amountHp) {
        long walletId = walletFacade.getOrCreateWallet(userId).getId();
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "earning-order-" + userId, Timestamp.from(FUNDED_AT),
                Timestamp.from(FUNDED_AT));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, FUNDED_AT,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
    }

    private Lecture saveLecture(String title) {
        LocalDateTime registeredAt = LocalDateTime.of(2026, 10, 1, 12, 0);
        Lecture lecture = Lecture.register(INFLUENCER, title, "설명", PRICE_HP, null,
                List.of(new LectureContentSpec(101L, "1강", ContentType.VIDEO)), registeredAt);
        lecture.approve(1L, registeredAt);
        return lectureRepository.save(lecture);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
