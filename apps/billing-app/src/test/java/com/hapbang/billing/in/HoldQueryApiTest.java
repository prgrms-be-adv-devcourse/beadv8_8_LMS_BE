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
import com.hapbang.billing.app.PromotionFundingCommand;
import com.hapbang.billing.app.PromotionFundingUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionFundingSourceType;
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

    @Autowired
    PromotionFundingUseCase promotionFundingUseCase;

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
    @DisplayName("프로모션 보류 조회는 쿠폰 순서·예약 ID·재원별 합계를 주고 구매자 0원 앵커도 HELD로 조회한다")
    void queryPromotionHoldsAndAnchor() throws Exception {
        String reservation42 = promotionBudget(42L, 10_000L);
        String reservation43 = promotionBudget(43L, 10_000L);
        call("/holds", promotionHold(31L, 8_000L, promotion(43L, 902L, 1L, 300L),
                promotion(42L, 901L, 2L, 700L))).andExpect(status().isOk());
        call("/holds", promotionHold(32L, 0L, promotion(42L, 903L, 0L, 500L)))
                .andExpect(status().isOk());

        queryLecture(List.of(31L, 32L, 99L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].hold.original.totalHp").value(8_000))
                .andExpect(jsonPath("$.data[0].hold.remaining.totalHp").value(8_000))
                .andExpect(jsonPath("$.data[0].promotions[*].couponId").value(Matchers.contains(42, 43)))
                .andExpect(jsonPath("$.data[0].promotions[0].memberCouponId").value(901))
                .andExpect(jsonPath("$.data[0].promotions[0].generation").value(2))
                .andExpect(jsonPath("$.data[0].promotions[0].reservationId").value(reservation42))
                .andExpect(jsonPath("$.data[0].promotions[0].amountHp").value(700))
                .andExpect(jsonPath("$.data[0].promotions[0].status").value("HELD"))
                .andExpect(jsonPath("$.data[0].promotions[1].reservationId").value(reservation43))
                .andExpect(jsonPath("$.data[0].promotion.held").value(1_000))
                .andExpect(jsonPath("$.data[0].promotion.paid").value(0))
                .andExpect(jsonPath("$.data[0].promotion.restored").value(0))
                .andExpect(jsonPath("$.data[1].hold.status").value("HELD"))
                .andExpect(jsonPath("$.data[1].hold.original.totalHp").value(0))
                .andExpect(jsonPath("$.data[1].hold.remaining.totalHp").value(0))
                .andExpect(jsonPath("$.data[1].promotion.held").value(500))
                .andExpect(jsonPath("$.data[2].hold").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[2].promotions").doesNotExist())
                .andExpect(jsonPath("$.data[2].promotion").doesNotExist());
    }

    @Test
    @DisplayName("지급·반환·0원 앵커 무효화 뒤 조회는 프로모션 최종 상태와 판매자 지급의 구매자·플랫폼 몫을 분리한다")
    void queryCompletedPromotionActions() throws Exception {
        promotionBudget(42L, 10_000L);
        call("/holds", promotionHold(31L, 8_000L, promotion(42L, 901L, 2L, 1_000L)))
                .andExpect(status().isOk());
        call("/holds", promotionHold(32L, 2_000L, promotion(42L, 902L, 0L, 500L)))
                .andExpect(status().isOk());
        call("/holds", promotionHold(33L, 0L, promotion(42L, 903L, 0L, 500L)))
                .andExpect(status().isOk());
        call("/payouts", lecturePayout(31L, "P31")).andExpect(status().isOk());
        call("/releases", release(HOST, 32L, "R32", null)
                .replace("APPEARANCE_REQUEST", "LECTURE_ORDER_ITEM")).andExpect(status().isOk());
        call("/hold-voids", holdVoid(HOST, 33L).replace("APPEARANCE_REQUEST", "LECTURE_ORDER_ITEM"))
                .andExpect(status().isOk());

        queryLecture(List.of(31L, 32L, 33L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].hold.status").value("PAID"))
                .andExpect(jsonPath("$.data[0].hold.paid.totalHp").value(8_000))
                .andExpect(jsonPath("$.data[0].promotions[0].status").value("PAID"))
                .andExpect(jsonPath("$.data[0].promotion.held").value(0))
                .andExpect(jsonPath("$.data[0].promotion.paid").value(1_000))
                .andExpect(jsonPath("$.data[0].promotion.restored").value(0))
                .andExpect(jsonPath("$.data[0].settlements.length()").value(1))
                .andExpect(jsonPath("$.data[0].settlements[0].grossAmountHp").value(9_000))
                .andExpect(jsonPath("$.data[0].settlements[0].paidAmountHp").value(9_000))
                .andExpect(jsonPath("$.data[0].settlements[0].buyerPaidHp").value(8_000))
                .andExpect(jsonPath("$.data[0].settlements[0].promotionPaidHp").value(1_000))
                .andExpect(jsonPath("$.data[1].hold.status").value("RELEASED"))
                .andExpect(jsonPath("$.data[1].hold.released.totalHp").value(2_000))
                .andExpect(jsonPath("$.data[1].promotions[0].status").value("RESTORED"))
                .andExpect(jsonPath("$.data[1].promotion.held").value(0))
                .andExpect(jsonPath("$.data[1].promotion.paid").value(0))
                .andExpect(jsonPath("$.data[1].promotion.restored").value(500))
                .andExpect(jsonPath("$.data[1].settlements").isEmpty())
                .andExpect(jsonPath("$.data[2].hold.status").value("RELEASED"))
                .andExpect(jsonPath("$.data[2].hold.released.totalHp").value(0))
                .andExpect(jsonPath("$.data[2].promotions[0].status").value("RESTORED"))
                .andExpect(jsonPath("$.data[2].promotion.restored").value(500))
                .andExpect(jsonPath("$.data[2].voided.outcome").value("RELEASED"))
                .andExpect(jsonPath("$.data[2].voided.releasedHp").value(0));
    }

    @Test
    @DisplayName("변경 목록도 0원 앵커의 프로모션 보류·지급·복구를 마지막 변경 순서대로 조회한다")
    void listPromotionHoldsAndCompletedActions() throws Exception {
        String reservationId = promotionBudget(42L, 10_000L);
        call("/holds", promotionHold(31L, 0L, promotion(42L, 901L, 2L, 1_000L)))
                .andExpect(status().isOk());
        call("/holds", promotionHold(32L, 0L, promotion(42L, 902L, 0L, 500L)))
                .andExpect(status().isOk());
        call("/holds", promotionHold(33L, 0L, promotion(42L, 903L, 0L, 500L)))
                .andExpect(status().isOk());
        call("/payouts", lecturePayout(32L, "P32")).andExpect(status().isOk());
        call("/releases", release(HOST, 33L, "R33", null)
                .replace("APPEARANCE_REQUEST", "LECTURE_ORDER_ITEM")).andExpect(status().isOk());
        changedMinutesAgo(BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 5);
        changedMinutesAgo(BillingReferenceType.LECTURE_ORDER_ITEM, 32L, 4);
        changedMinutesAgo(BillingReferenceType.LECTURE_ORDER_ITEM, 33L, 3);

        list("LECTURE_ORDER_ITEM", Instant.EPOCH.toString(), 0L, 100)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].referenceId").value(Matchers.contains(31, 32, 33)))
                .andExpect(jsonPath("$.data.items[0].promotions[0].reservationId").value(reservationId))
                .andExpect(jsonPath("$.data.items[0].hold.status").value("HELD"))
                .andExpect(jsonPath("$.data.items[0].promotion.held").value(1_000))
                .andExpect(jsonPath("$.data.items[1].hold.status").value("PAID"))
                .andExpect(jsonPath("$.data.items[1].hold.paid.totalHp").value(0))
                .andExpect(jsonPath("$.data.items[1].promotion.paid").value(500))
                .andExpect(jsonPath("$.data.items[1].settlements[0].buyerPaidHp").value(0))
                .andExpect(jsonPath("$.data.items[1].settlements[0].promotionPaidHp").value(500))
                .andExpect(jsonPath("$.data.items[2].hold.status").value("RELEASED"))
                .andExpect(jsonPath("$.data.items[2].promotion.restored").value(500));
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
    @DisplayName("참조 유형별로 바뀐 순서대로 size개씩 주고, hasMore가 false가 될 때까지 커서로 이어 읽는다. 마지막 커서로 다시 부르면 "
            + "빈 페이지에 같은 커서다. 다른 참조 유형은 섞이지 않는다")
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
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andReturn().getResponse().getContentAsString();

        String last = list("APPEARANCE_REQUEST", JsonPath.read(first, "$.data.nextUpdatedAfter"),
                ((Number) JsonPath.read(first, "$.data.nextAfterId")).longValue(), 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].referenceId").value(Matchers.contains(31)))
                .andExpect(jsonPath("$.data.items[0].hold.released.totalHp").value(500))
                .andExpect(jsonPath("$.data.nextUpdatedAfter").isString())
                .andExpect(jsonPath("$.data.nextAfterId").isNumber())
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andReturn().getResponse().getContentAsString();

        // 다음 대사: 저장한 마지막 커서로 부르면 그 뒤에 바뀐 것만 온다(없으면 빈 페이지에 같은 커서)
        String cursorAt = JsonPath.read(last, "$.data.nextUpdatedAfter");
        long cursorId = ((Number) JsonPath.read(last, "$.data.nextAfterId")).longValue();
        list("APPEARANCE_REQUEST", cursorAt, cursorId, 2)
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextUpdatedAfter").value(cursorAt))
                .andExpect(jsonPath("$.data.nextAfterId").value(cursorId))
                .andExpect(jsonPath("$.data.hasMore").value(false));

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
    @DisplayName("마지막 변경 시각이 같은 보류는 커서의 보류 위치로 나눠, 페이지 경계에서 빠지거나 겹치지 않는다")
    void listSplitsSameUpdatedAt() throws Exception {
        call("/holds", hold(HOST, 61, 1_000)).andExpect(status().isOk());
        call("/holds", hold(HOST, 62, 1_000)).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE billing.point_hold SET updated_at = date_trunc('second', now()) - interval '5 minutes' "
                + "WHERE reference_type = ? AND reference_id IN (61, 62)", BillingReferenceType.APPEARANCE_REQUEST.name());

        String first = list("APPEARANCE_REQUEST", Instant.EPOCH.toString(), 0, 1)
                .andExpect(jsonPath("$.data.items[*].referenceId").value(Matchers.contains(61)))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andReturn().getResponse().getContentAsString();
        String cursorAt = JsonPath.read(first, "$.data.nextUpdatedAfter");

        list("APPEARANCE_REQUEST", cursorAt, ((Number) JsonPath.read(first, "$.data.nextAfterId")).longValue(), 1)
                .andExpect(jsonPath("$.data.items[*].referenceId").value(Matchers.contains(62)))
                .andExpect(jsonPath("$.data.nextUpdatedAfter").value(cursorAt))
                .andExpect(jsonPath("$.data.hasMore").value(false));
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

    private ResultActions queryLecture(List<Long> ids) throws Exception {
        return call("/holds/query", "{\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceIds\":"
                + ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]")) + "}");
    }

    private String promotionBudget(long couponId, long amountHp) throws Exception {
        promotionFundingUseCase.fund(900L, new PromotionFundingCommand("PF-" + couponId, amountHp,
                PromotionFundingSourceType.ADMIN_GRANT, "결재-" + couponId, null));
        String reserved = call("/promotion-budgets/reservations", """
                {"workId":"coupon-%d-reserve-v1","couponId":%d,"policyVersion":1,"amountHp":%d}
                """.formatted(couponId, couponId, amountHp))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 보류 조회의 예약 ID가 쿠폰 예산 API가 실제로 내보낸 표기와 같은지 확인한다.
        return JsonPath.read(reserved, "$.data.reservationId");
    }

    private static String promotion(long couponId, long memberCouponId, long generation, long amountHp) {
        return "{\"couponId\":%d,\"memberCouponId\":%d,\"generation\":%d,\"amountHp\":%d}".formatted(couponId,
                memberCouponId, generation, amountHp);
    }

    private static String promotionHold(long referenceId, long buyerHp, String... promotions) {
        return """
                {"userId":%d,"referenceType":"LECTURE_ORDER_ITEM","referenceId":%d,"amountHp":%d,
                 "promotions":[%s]}""".formatted(HOST, referenceId, buyerHp, String.join(",", promotions));
    }

    private static String lecturePayout(long referenceId, String requestId) {
        return """
                {"payerUserId":%d,"referenceType":"LECTURE_ORDER_ITEM","referenceId":%d,"payoutRequestId":"%s",
                 "receiverUserId":%d,"sourceType":"LECTURE_ORDER_ITEM","sourceId":%d,"amountHp":null}"""
                .formatted(HOST, referenceId, requestId, GUEST, referenceId);
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
