package com.hapbang.billing.in;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.testsupport.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

// 서비스 간 HP 대사용 보류·지급 조회 내부 API (#577, BD-137). 실제 내부 API로 보류·반환·지급·위약금·무효화를 만든 뒤 조회 결과를 확인한다.
@IntegrationTest
@AutoConfigureMockMvc
class HoldQueryApiTest {

    private static final String BASE = "/api/v1/internal/billing";
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final long MIXED_PAYER = 3L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        revenue(wallet(HOST), 100_000L);
        wallet(GUEST);
    }

    // ---------------------------------------------------------------------------------------------
    // 참조 대상별 조회
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("보류만·일부 반환·지급·위약금·무효화만·기록 없음을 요청 순서대로(같은 ID는 한 번) 주고, 금액은 최초 = 남은 + 반환 + 지급 + 확정 차감이다")
    void query() throws Exception {
        call("/holds", hold(HOST, 11, 10_000)).andExpect(status().isOk());
        call("/holds", hold(HOST, 12, 10_000)).andExpect(status().isOk());
        call("/releases", release(HOST, 12, "R12", 3_000L)).andExpect(status().isOk());
        call("/holds", hold(HOST, 13, 10_000)).andExpect(status().isOk());
        call("/payouts", payout(13, "P13", 131)).andExpect(status().isOk());
        call("/holds", hold(HOST, 14, 10_000)).andExpect(status().isOk());
        call("/penalty-settlements", penalty(14, "C14", 141, 7_000, 3_000)).andExpect(status().isOk());
        call("/hold-voids", holdVoid(HOST, 15)).andExpect(status().isOk());

        query(List.of(16L, 11L, 12L, 13L, 14L, 15L, 11L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("HP 보류·지급을 조회했습니다."))
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[*].referenceId").value(Matchers.contains(16, 11, 12, 13, 14, 15)))
                // 기록 없음
                .andExpect(jsonPath("$.data[0].referenceType").value("APPEARANCE_REQUEST"))
                .andExpect(jsonPath("$.data[0].hold").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].voided").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].settlements").isEmpty())
                // 보류만
                .andExpect(jsonPath("$.data[1].hold.status").value("HELD"))
                .andExpect(jsonPath("$.data[1].hold.payerUserId").value(HOST))
                .andExpect(jsonPath("$.data[1].hold.original.totalHp").value(10_000))
                .andExpect(jsonPath("$.data[1].hold.remaining.totalHp").value(10_000))
                .andExpect(jsonPath("$.data[1].hold.released.totalHp").value(0))
                .andExpect(jsonPath("$.data[1].hold.paid.totalHp").value(0))
                .andExpect(jsonPath("$.data[1].hold.captured.totalHp").value(0))
                // 일부 반환
                .andExpect(jsonPath("$.data[2].hold.status").value("PARTIALLY_PROCESSED"))
                .andExpect(jsonPath("$.data[2].hold.remaining.totalHp").value(7_000))
                .andExpect(jsonPath("$.data[2].hold.released.totalHp").value(3_000))
                .andExpect(jsonPath("$.data[2].settlements").isEmpty())
                // 전체 지급
                .andExpect(jsonPath("$.data[3].hold.status").value("PAID"))
                .andExpect(jsonPath("$.data[3].hold.remaining.totalHp").value(0))
                .andExpect(jsonPath("$.data[3].hold.paid.totalHp").value(10_000))
                .andExpect(jsonPath("$.data[3].settlements.length()").value(1))
                .andExpect(jsonPath("$.data[3].settlements[0].sourceType").value("APPEARANCE_REQUEST"))
                .andExpect(jsonPath("$.data[3].settlements[0].sourceId").value(131))
                .andExpect(jsonPath("$.data[3].settlements[0].payoutType").value("NORMAL"))
                .andExpect(jsonPath("$.data[3].settlements[0].receiverUserId").value(GUEST))
                .andExpect(jsonPath("$.data[3].settlements[0].paidAmountHp").value(10_000))
                .andExpect(jsonPath("$.data[3].settlements[0].status").value("COMPLETED"))
                // 위약금(반환 + 지급)
                .andExpect(jsonPath("$.data[4].hold.status").value("MIXED"))
                .andExpect(jsonPath("$.data[4].hold.released.totalHp").value(7_000))
                .andExpect(jsonPath("$.data[4].hold.paid.totalHp").value(3_000))
                .andExpect(jsonPath("$.data[4].settlements[0].payoutType").value("PENALTY"))
                .andExpect(jsonPath("$.data[4].settlements[0].paidAmountHp").value(3_000))
                // 보류 없이 무효화만
                .andExpect(jsonPath("$.data[5].hold").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[5].voided.outcome").value("VOIDED"))
                .andExpect(jsonPath("$.data[5].voided.releasedHp").value(0));
    }

    @Test
    @DisplayName("충전 HP와 수익 HP가 섞인 보류는 종류별 금액과 합계를 주고, 무효화로 반환하면 무효화 결과와 반환액이 함께 나온다")
    void hpTypes() throws Exception {
        Long walletId = wallet(MIXED_PAYER);
        charge(walletId, 4_000L);
        revenue(walletId, 6_000L);
        call("/holds", hold(MIXED_PAYER, 21, 10_000)).andExpect(status().isOk());
        call("/hold-voids", holdVoid(MIXED_PAYER, 21)).andExpect(status().isOk());

        query(List.of(21L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].hold.status").value("RELEASED"))
                .andExpect(jsonPath("$.data[0].hold.original.revenueHp").value(6_000))
                .andExpect(jsonPath("$.data[0].hold.original.chargeHp").value(4_000))
                .andExpect(jsonPath("$.data[0].hold.original.totalHp").value(10_000))
                .andExpect(jsonPath("$.data[0].hold.remaining.totalHp").value(0))
                .andExpect(jsonPath("$.data[0].hold.released.chargeHp").value(4_000))
                .andExpect(jsonPath("$.data[0].hold.released.revenueHp").value(6_000))
                .andExpect(jsonPath("$.data[0].voided.outcome").value("RELEASED"))
                .andExpect(jsonPath("$.data[0].voided.releasedHp").value(10_000));
    }

    @Test
    @DisplayName("참조 ID 0개·501개, 보류할 수 없는 참조 유형은 400 INVALID_REQUEST이고, 내부 API 키가 없으면 401이다")
    void queryRejected() throws Exception {
        problem(call("/holds/query", "{\"referenceType\":\"APPEARANCE_REQUEST\",\"referenceIds\":[]}"), 400,
                "INVALID_REQUEST");
        problem(call("/holds/query", "{\"referenceType\":\"APPEARANCE_REQUEST\",\"referenceIds\":"
                + LongStream.rangeClosed(1, 501).boxed().toList() + "}"), 400, "INVALID_REQUEST");
        problem(call("/holds/query", "{\"referenceType\":\"LECTURE_ORDER\",\"referenceIds\":[1]}"), 400,
                "INVALID_REQUEST");
        mockMvc.perform(post(BASE + "/holds/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"referenceType\":\"APPEARANCE_REQUEST\",\"referenceIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------------------------------
    // 기간 안에 바뀐 보류 목록
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("참조 유형별로 바뀐 순서대로 size개씩 주고, 다음 커서로 이어서 끝까지 읽으면 커서가 null이다. 다른 참조 유형은 섞이지 않는다")
    void listChanged() throws Exception {
        call("/holds", hold(HOST, 31, 1_000)).andExpect(status().isOk());
        call("/holds", hold(HOST, 32, 1_000)).andExpect(status().isOk());
        call("/holds", hold(HOST, 33, 1_000)).andExpect(status().isOk());
        call("/holds", lectureHold(41, 1_000)).andExpect(status().isOk());
        call("/releases", release(HOST, 31, "R31", 500L)).andExpect(status().isOk());
        // 확정 대기 시간(기본 10초)보다 전에 바뀐 것으로 옮겨 순서를 정한다. 31은 마지막에 바뀌었다(반환)
        changedMinutesAgo(BillingReferenceType.APPEARANCE_REQUEST, 32, 5);
        changedMinutesAgo(BillingReferenceType.APPEARANCE_REQUEST, 33, 4);
        changedMinutesAgo(BillingReferenceType.APPEARANCE_REQUEST, 31, 3);
        changedMinutesAgo(BillingReferenceType.LECTURE_ORDER_ITEM, 41, 5);

        String first = list("APPEARANCE_REQUEST", Instant.EPOCH.toString(), 0, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].referenceId").value(Matchers.contains(32, 33)))
                .andExpect(jsonPath("$.data.nextUpdatedAfter").isString())
                .andExpect(jsonPath("$.data.nextAfterId").isNumber())
                .andReturn().getResponse().getContentAsString();

        list("APPEARANCE_REQUEST", JsonPath.read(first, "$.data.nextUpdatedAfter"),
                ((Number) JsonPath.read(first, "$.data.nextAfterId")).longValue(), 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].referenceId").value(Matchers.contains(31)))
                .andExpect(jsonPath("$.data.items[0].hold.released.totalHp").value(500))
                .andExpect(jsonPath("$.data.nextUpdatedAfter").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.nextAfterId").value(Matchers.nullValue()));

        list("LECTURE_ORDER_ITEM", Instant.EPOCH.toString(), 0, 100)
                .andExpect(jsonPath("$.data.items[*].referenceId").value(Matchers.contains(41)));
    }

    @Test
    @DisplayName("확정 대기 시간(10초) 안에 바뀐 보류는 아직 커밋 전일 수 있어 목록에 없고, 지나면 마지막 변경 순서대로 나온다")
    void listSkipsUnsettled() throws Exception {
        call("/holds", hold(HOST, 51, 1_000)).andExpect(status().isOk());
        call("/holds", hold(HOST, 52, 1_000)).andExpect(status().isOk());
        changedMinutesAgo(BillingReferenceType.APPEARANCE_REQUEST, 51, 5);

        list("APPEARANCE_REQUEST", Instant.EPOCH.toString(), 0, 100)
                .andExpect(jsonPath("$.data.items[*].referenceId").value(Matchers.contains(51)));

        changedMinutesAgo(BillingReferenceType.APPEARANCE_REQUEST, 52, 1);
        list("APPEARANCE_REQUEST", Instant.EPOCH.toString(), 0, 100)
                .andExpect(jsonPath("$.data.items[*].referenceId").value(Matchers.contains(51, 52)));
        // 참조 대상별 조회는 확정 대기 시간과 관계없다
        query(List.of(52L)).andExpect(jsonPath("$.data[0].hold.status").value("HELD"));
    }

    @Test
    @DisplayName("size 0·501, afterId 음수, 보류할 수 없는 참조 유형, 시각 형식 오류는 400 INVALID_REQUEST다")
    void listRejected() throws Exception {
        String epoch = Instant.EPOCH.toString();
        problem(list("APPEARANCE_REQUEST", epoch, 0, 0), 400, "INVALID_REQUEST");
        problem(list("APPEARANCE_REQUEST", epoch, 0, 501), 400, "INVALID_REQUEST");
        problem(list("APPEARANCE_REQUEST", epoch, -1, 10), 400, "INVALID_REQUEST");
        problem(list("LECTURE_ORDER", epoch, 0, 10), 400, "INVALID_REQUEST");
        problem(list("APPEARANCE_REQUEST", "2026-11-01", 0, 10), 400, "INVALID_REQUEST");
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private ResultActions call(String path, String body) throws Exception {
        return mockMvc.perform(post(BASE + path).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions query(List<Long> ids) throws Exception {
        return call("/holds/query", "{\"referenceType\":\"APPEARANCE_REQUEST\",\"referenceIds\":"
                + ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]")) + "}");
    }

    private ResultActions list(String referenceType, String updatedAfter, long afterId, int size) throws Exception {
        return mockMvc.perform(get(BASE + "/holds").header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                .param("referenceType", referenceType).param("updatedAfter", updatedAfter)
                .param("afterId", String.valueOf(afterId)).param("size", String.valueOf(size)));
    }

    private static void problem(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    private static String hold(long userId, long referenceId, long amountHp) {
        return """
                {"userId": %d, "referenceType": "APPEARANCE_REQUEST", "referenceId": %d, "amountHp": %d}"""
                .formatted(userId, referenceId, amountHp);
    }

    private static String lectureHold(long referenceId, long amountHp) {
        return """
                {"userId": %d, "referenceType": "LECTURE_ORDER_ITEM", "referenceId": %d, "amountHp": %d}"""
                .formatted(HOST, referenceId, amountHp);
    }

    private static String release(long userId, long referenceId, String requestId, Long amountHp) {
        return """
                {"userId": %d, "referenceType": "APPEARANCE_REQUEST", "referenceId": %d, "releaseRequestId": "%s",
                 "amountHp": %s}""".formatted(userId, referenceId, requestId, amountHp);
    }

    private static String payout(long referenceId, String requestId, long contractId) {
        return """
                {"payerUserId": %d, "referenceType": "APPEARANCE_REQUEST", "referenceId": %d, "payoutRequestId": "%s",
                 "receiverUserId": %d, "sourceType": "APPEARANCE_REQUEST", "sourceId": %d, "amountHp": null}"""
                .formatted(HOST, referenceId, requestId, GUEST, contractId);
    }

    private static String penalty(long referenceId, String cancellationId, long contractId, long releaseHp,
                                  long payoutHp) {
        return """
                {"payerUserId": %d, "referenceType": "APPEARANCE_REQUEST", "referenceId": %d, "cancellationId": "%s",
                 "receiverUserId": %d, "sourceType": "APPEARANCE_REQUEST", "sourceId": %d,
                 "releaseAmountHp": %d, "payoutAmountHp": %d}"""
                .formatted(HOST, referenceId, cancellationId, GUEST, contractId, releaseHp, payoutHp);
    }

    private static String holdVoid(long userId, long referenceId) {
        return """
                {"userId": %d, "referenceType": "APPEARANCE_REQUEST", "referenceId": %d}""".formatted(userId,
                referenceId);
    }

    // 보류의 마지막 변경 시각을 지금보다 minutes분 전으로 옮긴다(확정 대기 시간 밖으로)
    private void changedMinutesAgo(BillingReferenceType referenceType, long referenceId, int minutes) {
        jdbcTemplate.update("UPDATE billing.point_hold SET updated_at = now() - make_interval(mins => ?) "
                + "WHERE reference_type = ? AND reference_id = ?", minutes, referenceType.name(), referenceId);
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

    // 완료된 충전 건과 그만큼의 충전 원장을 만든다(보류의 충전 HP 배분에 쓰인다).
    private void charge(Long walletId, long amountHp) {
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class, walletId, amountHp, amountHp, amountHp,
                "order-" + sequence.incrementAndGet(), Timestamp.from(T1), Timestamp.from(T1));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
    }
}
