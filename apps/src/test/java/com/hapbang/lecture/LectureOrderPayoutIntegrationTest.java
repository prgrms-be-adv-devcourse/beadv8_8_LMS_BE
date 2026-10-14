package com.hapbang.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.WalletFacade;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.global.exception.BusinessException;
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.port.BillingPort;
import com.hapbang.shared.lecture.port.LectureObjectionPort;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

/**
 * 강의 판매 수익을 강의가 shared {@link BillingPort#payout}으로 직접 지급하는 방식(#188)을 실제 billing·PostgreSQL로 확인한다.
 * <ul>
 *     <li>구매·구매 취소는 보류·반환만 billing을 부르고 지급 건(SETTLEMENT)을 만들지 않는다. 강의 주문 변경과 한 트랜잭션이다.</li>
 *     <li>지급은 강의(#150)가 지급 시점·이의 제기·판매자 상태를 판단한 뒤 부른다. 강의 지급 코드는 아직 dev에 없으므로
 *     #150의 호출 형태({@code PayoutCommand.full(구매자, LECTURE_ORDER_ITEM, 항목 ID, "LECTURE-PAYOUT-{항목 ID}", 판매자,
 *     LECTURE_ORDER_ITEM, 항목 ID)})로 billing을 직접 부른다.</li>
 * </ul>
 * 잔액은 billing에 충전 API가 없어 충전 건·충전 원장(수익 HP는 지급 원장)을 직접 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class LectureOrderPayoutIntegrationTest {

    private static final Long SELLER_ID = 999_998L;
    private static final Long SELLER_WITHOUT_WALLET_ID = 999_997L;
    private static final long PRICE_HP = 10_000L;
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final BillingReferenceType ITEM = BillingReferenceType.LECTURE_ORDER_ITEM;
    private static final SettlementSourceType ITEM_SOURCE = SettlementSourceType.LECTURE_ORDER_ITEM;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    LectureRepository lectureRepository;

    @Autowired
    LectureOrderRepository lectureOrderRepository;

    @Autowired
    WalletFacade walletFacade;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    BillingPort billingPort;

    @Autowired
    LectureObjectionPort lectureObjectionPort;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final AtomicInteger sequence = new AtomicInteger();

    @Test
    void shared_BillingPort는_billing의_실제_Adapter로_주입된다() {
        assertThat(AopUtils.getTargetClass(billingPort).getName()).isEqualTo("com.hapbang.billing.in.BillingPortAdapter");
    }

    // ---------------------------------------------------------------------------------------------
    // 구매·구매 취소: 보류·반환만, 지급 건 없음
    // ---------------------------------------------------------------------------------------------

    @Test
    void 주문하면_보류만_주문과_함께_커밋되고_지급_건은_만들지_않는다() {
        User buyer = buyer("payout-order");
        Long buyerWalletId = fundCharge(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);

        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));

        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
        assertThat(settlementCount()).isZero();
        assertThat(wallet(buyerWalletId)).containsEntry("charge_balance_hp", 0L)
                .containsEntry("held_charge_hp", PRICE_HP);
    }

    @Test
    void 구매_시점에는_판매자_지갑을_보지_않아_지갑이_없어도_주문되고_지갑을_만들지_않는다() {
        User buyer = buyer("payout-no-seller-wallet");
        fundCharge(buyer.getId(), PRICE_HP);

        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_WITHOUT_WALLET_ID).getId()));

        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
        assertThat(sellerWalletCount(SELLER_WITHOUT_WALLET_ID)).isZero();
    }

    @Test
    void 구매_취소하면_보류_반환이_구매_취소와_함께_커밋되고_지급_건은_없다() throws Exception {
        User buyer = buyer("payout-cancel");
        Long buyerWalletId = fundCharge(buyer.getId(), PRICE_HP);
        Map<String, Object> order = order(buyer, saveOnSaleLecture(SELLER_ID).getId());
        Long orderItemId = orderItemId(order);

        mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId(order),
                        orderItemId).with(csrf()).with(TestLogin.as(buyer.getId(), Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 RELEASED");
        assertThat(settlementCount()).isZero();
        assertThat(wallet(buyerWalletId)).containsEntry("charge_balance_hp", PRICE_HP);
    }

    @Test
    void 반환이_실패하면_구매_취소가_모두_롤백된다() throws Exception {
        User buyer = buyer("payout-release-fail");
        Long buyerWalletId = fundCharge(buyer.getId(), PRICE_HP);
        Map<String, Object> order = order(buyer, saveOnSaleLecture(SELLER_ID).getId());
        Long orderItemId = orderItemId(order);

        failOn("point_hold_detail", () -> mockMvc.perform(post(
                        "/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId(order), orderItemId)
                        .with(csrf()).with(TestLogin.as(buyer.getId(), Role.USER)))
                .andExpect(status().isInternalServerError()));

        assertThat(itemStatus(orderItemId)).isEqualTo("PURCHASED");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
        assertThat(wallet(buyerWalletId)).containsEntry("charge_balance_hp", 0L);
    }

    // ---------------------------------------------------------------------------------------------
    // 강의 직접 지급 (BillingPort.payout)
    // ---------------------------------------------------------------------------------------------

    @Test
    void 주문_항목의_보류_전부를_판매자_수익_HP로_지급하고_지급_건_COMPLETED와_원장을_남긴다() {
        User buyer = buyer("payout-full");
        // 구매자 HP: 수익 3,000 + 충전 7,000 → 보류도 수익 3,000 + 충전 7,000 (수익 HP 먼저)
        Long buyerWalletId = fundRevenue(buyer.getId(), 3_000L);
        fundCharge(buyer.getId(), 7_000L);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));

        PayoutResultDto result = billingPort.payout(payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId));

        assertThat(result).isEqualTo(new PayoutResultDto(ITEM, orderItemId, "LECTURE-PAYOUT-" + orderItemId, SELLER_ID,
                ITEM_SOURCE, orderItemId, PRICE_HP, 0L, false));
        Long holdId = holdId(orderItemId);
        String key = "PAYOUT:" + holdId + ":LECTURE-PAYOUT-" + orderItemId;
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 PAID");
        // 지급은 수익 HP 보류를 먼저, 부족분만 충전 HP 보류에서 쓴다
        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_key || ' ' || action_type || ' ' || revenue_amount_hp || ' ' || charge_amount_hp "
                        + "FROM billing.point_hold_detail", String.class))
                .containsExactly(key + " PAYOUT 3000 7000");
        assertThat(settlements()).containsExactly(key + " " + SELLER_ID + " " + sellerWalletId
                + " LECTURE_ORDER_ITEM " + orderItemId + " NORMAL 10000 10000 COMPLETED");
        assertThat(ledgerLines(key)).containsExactly(
                buyerWalletId + " REVENUE HELD -3000",
                buyerWalletId + " CHARGE HELD -7000",
                sellerWalletId + " REVENUE AVAILABLE 10000");
        assertThat(wallet(buyerWalletId)).containsEntry("held_charge_hp", 0L).containsEntry("held_revenue_hp", 0L);
        assertThat(wallet(sellerWalletId)).containsEntry("revenue_balance_hp", PRICE_HP);
    }

    @Test
    void 같은_지급_요청을_다시_보내면_처음_결과를_돌려주고_HP_원장_지급_건이_중복되지_않는다() {
        User buyer = buyer("payout-replay");
        fundCharge(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        PayoutCommand command = payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId);
        PayoutResultDto first = billingPort.payout(command);
        Map<String, Object> before = financialSnapshot();

        PayoutResultDto retried = billingPort.payout(command);

        assertThat(retried.replayed()).isTrue();
        assertThat(retried.paidHp()).isEqualTo(first.paidHp());
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(settlementCount()).isEqualTo(1);
        assertThat(wallet(sellerWalletId)).containsEntry("revenue_balance_hp", PRICE_HP);
    }

    @Test
    void 전액_지급한_주문_항목을_다른_지급_요청_ID로_다시_지급하면_보류가_끝나_HOLD_ALREADY_CLOSED이고_아무것도_바뀌지_않는다() {
        User buyer = buyer("payout-duplicate-full");
        fundCharge(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        billingPort.payout(payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId));
        Map<String, Object> before = financialSnapshot();

        // 보류가 끝났는지를 원천 중복보다 먼저 확인하므로 끝난 보류에는 HOLD_ALREADY_CLOSED다(기존 지급 엔진 순서).
        assertBillingError(() -> billingPort.payout(payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId + "-2")),
                "HOLD_ALREADY_CLOSED");

        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(settlementCount()).isEqualTo(1);
    }

    @Test
    void 보류가_남은_주문_항목을_다른_지급_요청_ID로_다시_지급하면_같은_원천이라_PAYOUT_ALREADY_SETTLED이다() {
        User buyer = buyer("payout-duplicate-partial");
        fundCharge(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        billingPort.payout(PayoutCommand.partial(buyer.getId(), ITEM, orderItemId, "LECTURE-PAYOUT-" + orderItemId,
                SELLER_ID, ITEM_SOURCE, orderItemId, 4_000L));
        Map<String, Object> before = financialSnapshot();

        assertBillingError(() -> billingPort.payout(payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId + "-2")),
                "PAYOUT_ALREADY_SETTLED");

        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(settlementCount()).isEqualTo(1);
    }

    @Test
    void 잘못된_수령자_원천_보류와_금액은_거절하고_아무것도_바꾸지_않는다() {
        User buyer = buyer("payout-invalid");
        fundCharge(buyer.getId(), 2 * PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Long lectureId = saveOnSaleLecture(SELLER_ID).getId();
        Long otherLectureId = saveOnSaleLecture(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, lectureId));
        Long otherItemId = orderItemId(order(buyer, otherLectureId));
        Map<String, Object> before = financialSnapshot();
        String requestId = "LECTURE-PAYOUT-" + orderItemId;

        // 구매자 자신에게 지급
        assertBillingError(() -> billingPort.payout(PayoutCommand.full(buyer.getId(), ITEM, orderItemId, requestId,
                buyer.getId(), ITEM_SOURCE, orderItemId)), "PAYOUT_TO_PAYER");
        // 원천이 그 주문 항목이 아님(다른 항목 ID, 출연 원천 유형)
        assertBillingError(() -> billingPort.payout(PayoutCommand.full(buyer.getId(), ITEM, orderItemId, requestId,
                SELLER_ID, ITEM_SOURCE, otherItemId)), "PAYOUT_SOURCE_MISMATCH");
        assertBillingError(() -> billingPort.payout(PayoutCommand.full(buyer.getId(), ITEM, orderItemId, requestId,
                SELLER_ID, SettlementSourceType.APPEARANCE_REQUEST, orderItemId)), "PAYOUT_SOURCE_MISMATCH");
        // 없는 보류, 판매자 지갑 없음, 남은 보류보다 큰 부분 지급
        assertBillingError(() -> billingPort.payout(PayoutCommand.full(buyer.getId(), ITEM, 987_654L, requestId,
                SELLER_ID, ITEM_SOURCE, 987_654L)), "HOLD_NOT_FOUND");
        assertBillingError(() -> billingPort.payout(PayoutCommand.full(buyer.getId(), ITEM, orderItemId, requestId,
                SELLER_WITHOUT_WALLET_ID, ITEM_SOURCE, orderItemId)), "WALLET_NOT_FOUND");
        assertBillingError(() -> billingPort.payout(PayoutCommand.partial(buyer.getId(), ITEM, orderItemId, requestId,
                SELLER_ID, ITEM_SOURCE, orderItemId, PRICE_HP + 1)), "PAYOUT_AMOUNT_EXCEEDS_HELD");

        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(sellerWalletCount(SELLER_WITHOUT_WALLET_ID)).isZero();
    }

    @Test
    void 구매_취소로_반환된_주문_항목은_지급할_수_없다() throws Exception {
        User buyer = buyer("payout-after-cancel");
        fundCharge(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Map<String, Object> order = order(buyer, saveOnSaleLecture(SELLER_ID).getId());
        Long orderItemId = orderItemId(order);
        mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId(order),
                orderItemId).with(csrf()).with(TestLogin.as(buyer.getId(), Role.USER))).andExpect(status().isOk());
        Map<String, Object> before = financialSnapshot();

        assertBillingError(() -> billingPort.payout(payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId)),
                "HOLD_ALREADY_CLOSED");

        assertThat(financialSnapshot()).isEqualTo(before);
    }

    @Test
    void 지급_건_저장이_실패하면_보류_배분_원장_두_지갑이_모두_롤백되고_같은_요청으로_다시_지급할_수_있다() {
        User buyer = buyer("payout-failure");
        fundCharge(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        PayoutCommand command = payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId);
        Map<String, Object> before = financialSnapshot();

        failOn("settlement", () -> assertThatThrownBy(() -> billingPort.payout(command))
                .hasStackTraceContaining("test failure"));

        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(billingPort.payout(command).replayed()).isFalse();
        assertThat(settlementCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCHEDULED", "SUSPENDED"})
    void 이전_방식의_강의_지급_예정_건이_남은_구매_항목은_보류가_남아_있어도_직접_지급이_PAYOUT_ALREADY_SETTLED로_막힌다(String status) {
        // #142·#182 기간에 구매하며 만들어진 지급 예정 건(중단 포함)을 흉내 낸다(#188 전환 시 데이터 처리가 필요하다는 확인)
        User buyer = buyer("payout-legacy-" + status.toLowerCase());
        fundCharge(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        insertLegacySettlement(orderItemId, sellerWalletId, status);
        Map<String, Object> before = financialSnapshot();

        assertBillingError(() -> billingPort.payout(payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId)),
                "PAYOUT_ALREADY_SETTLED");

        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
    }

    @Test
    void 이전_방식에서_취소된_CANCELLED_건은_보류가_이미_반환되어_지급_대상이_아니고_HOLD_ALREADY_CLOSED다() throws Exception {
        User buyer = buyer("payout-legacy-cancelled");
        fundCharge(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Map<String, Object> order = order(buyer, saveOnSaleLecture(SELLER_ID).getId());
        Long orderItemId = orderItemId(order);
        mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId(order),
                orderItemId).with(csrf()).with(TestLogin.as(buyer.getId(), Role.USER))).andExpect(status().isOk());
        insertLegacySettlement(orderItemId, sellerWalletId, "CANCELLED");
        Map<String, Object> before = financialSnapshot();

        // 끝난 보류 확인이 원천 중복 확인보다 먼저라 CANCELLED 행과 관계없이 HOLD_ALREADY_CLOSED다
        assertBillingError(() -> billingPort.payout(payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId)),
                "HOLD_ALREADY_CLOSED");

        assertThat(financialSnapshot()).isEqualTo(before);
    }

    @Test
    void 판매자_지갑이_없어_지급이_실패하면_모두_롤백되고_지갑이_생긴_뒤_같은_요청으로_지급된다() {
        User buyer = buyer("payout-seller-wallet-later");
        Long buyerWalletId = fundCharge(buyer.getId(), PRICE_HP);
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_WITHOUT_WALLET_ID).getId()));
        PayoutCommand command = PayoutCommand.full(buyer.getId(), ITEM, orderItemId, "LECTURE-PAYOUT-" + orderItemId,
                SELLER_WITHOUT_WALLET_ID, ITEM_SOURCE, orderItemId);
        Map<String, Object> before = financialSnapshot();

        assertBillingError(() -> billingPort.payout(command), "WALLET_NOT_FOUND");

        // 지급 전 상태 그대로: 보류 HELD, 지급 상세·지급 건·원장 없음, 판매자 지갑을 만들지 않는다
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
        assertThat(sellerWalletCount(SELLER_WITHOUT_WALLET_ID)).isZero();

        // 판매자 지갑이 준비되면(가입·지갑 조회 경로) 같은 원천·같은 요청 ID로 다시 지급할 수 있다
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_WITHOUT_WALLET_ID).getId();
        PayoutResultDto paid = billingPort.payout(command);

        assertThat(paid.replayed()).isFalse();
        assertThat(paid.paidHp()).isEqualTo(PRICE_HP);
        assertThat(wallet(sellerWalletId)).containsEntry("revenue_balance_hp", PRICE_HP);
        assertThat(wallet(buyerWalletId)).containsEntry("held_charge_hp", 0L);
        assertThat(settlementCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // 이의 제기 인정 (#156): 보류 반환만, 지급 건 없음
    // ---------------------------------------------------------------------------------------------

    @Test
    void 이의_제기를_인정하면_보류_반환이_항목_반환과_함께_커밋되고_지급_건은_만들지_않는다() {
        User buyer = buyer("payout-objection-accept");
        Long buyerWalletId = fundCharge(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));

        // 고객센터처럼 문의 저장과 같은 트랜잭션에서 포트를 부른다
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                lectureObjectionPort.fileObjection(orderItemId, buyer.getId(), 1L, "강의 소개와 영상 내용이 다릅니다."));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                lectureObjectionPort.resolveObjection(orderItemId, 1L, true, 1L));

        assertThat(itemStatusAndObjection(orderItemId)).isEqualTo("RETURNED ACCEPTED");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 RELEASED");
        assertThat(jdbcTemplate.queryForList("SELECT idempotency_key FROM billing.point_hold_detail", String.class))
                .containsExactly("RELEASE:" + holdId(orderItemId) + ":LECTURE-RETURN-" + orderItemId);
        assertThat(settlementCount()).isZero();
        assertThat(wallet(buyerWalletId)).containsEntry("charge_balance_hp", PRICE_HP);
    }

    @Test
    void 지급이_끝난_항목은_보류가_닫혀_반환할_수_없어_이의_제기_인정이_billing_오류로_모두_롤백된다() {
        // 강의는 지급된 항목의 이의 제기 접수를 막고(ALREADY_PAID_OUT), 지급 배치(#150)는 이의 제기 중인 항목을 지급하지 않는다.
        // 그 순서를 지키지 않고 billing 지급이 먼저 끝난 경우에도 반환은 billing이 거절하고 판단은 롤백된다는 확인이다.
        User buyer = buyer("payout-objection-after-payout");
        fundCharge(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        lectureObjectionPort.fileObjection(orderItemId, buyer.getId(), 1L, "강의 소개와 영상 내용이 다릅니다.");
        billingPort.payout(payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId));
        Map<String, Object> before = financialSnapshot();

        assertBillingError(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                lectureObjectionPort.resolveObjection(orderItemId, 1L, true, 1L)), "HOLD_ALREADY_CLOSED");

        assertThat(itemStatusAndObjection(orderItemId)).isEqualTo("PURCHASED FILED");
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(wallet(sellerWalletId)).containsEntry("revenue_balance_hp", PRICE_HP);
    }

    @Test
    void 이의_제기_인정과_지급이_동시에_와도_보류는_한쪽으로만_처리되고_HP가_두_번_움직이지_않는다() throws Exception {
        User buyer = buyer("payout-objection-race");
        Long buyerWalletId = fundCharge(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        lectureObjectionPort.fileObjection(orderItemId, buyer.getId(), 1L, "강의 소개와 영상 내용이 다릅니다.");
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> accept = executor.submit(() -> attempt(start, () -> new TransactionTemplate(
                    transactionManager).executeWithoutResult(status ->
                    lectureObjectionPort.resolveObjection(orderItemId, 1L, true, 1L))));
            Future<Boolean> pay = executor.submit(() -> attempt(start,
                    () -> billingPort.payout(payout(buyer, orderItemId, "LECTURE-PAYOUT-" + orderItemId))));
            start.countDown();
            boolean accepted = accept.get(60, TimeUnit.SECONDS);
            boolean paid = pay.get(60, TimeUnit.SECONDS);

            // 보류는 구매자 지갑·보류 락으로 직렬화되어 정확히 한쪽만 성공한다
            assertThat(accepted ^ paid).isTrue();
            if (accepted) {
                assertThat(itemStatusAndObjection(orderItemId)).isEqualTo("RETURNED ACCEPTED");
                assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 RELEASED");
                assertThat(settlementCount()).isZero();
            } else {
                assertThat(itemStatusAndObjection(orderItemId)).isEqualTo("PURCHASED FILED");
                assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 PAID");
                assertThat(settlementCount()).isEqualTo(1);
            }
        }
        // 어느 쪽이든 HP 총량은 항목 가격 하나다(구매자 반환 또는 판매자 지급, 중복 없음)
        long buyerAvailable = (Long) wallet(buyerWalletId).get("charge_balance_hp");
        long sellerRevenue = (Long) wallet(sellerWalletId).get("revenue_balance_hp");
        assertThat(buyerAvailable + sellerRevenue).isEqualTo(PRICE_HP);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Integer.class))
                .isEqualTo(1);
    }

    // 시작 신호를 기다렸다가 실행하고 성공 여부를 돌려준다. billing 업무 예외(먼저 끝난 쪽 때문에 보류가 닫힘)는 실패로 센다.
    private static boolean attempt(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await(10, TimeUnit.SECONDS);
        try {
            action.run();
            return true;
        } catch (BusinessException exception) {
            assertThat(exception.getErrorCode().getCode()).isEqualTo("HOLD_ALREADY_CLOSED");
            return false;
        }
    }

    private String itemStatusAndObjection(Long orderItemId) {
        return jdbcTemplate.queryForObject("SELECT status || ' ' || objection_status FROM lectures.order_item "
                + "WHERE id = ?", String.class, orderItemId);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    // #142·#182 방식으로 구매 때 만들어졌던 강의 지급 건 행을 직접 넣는다(지금 코드는 이 행을 만들지 않는다).
    private void insertLegacySettlement(Long orderItemId, Long sellerWalletId, String status) {
        jdbcTemplate.update("""
                INSERT INTO billing.settlement (receiver_user_id, receiver_wallet_id, point_hold_id, source_type, source_id,
                    payout_type, gross_amount_hp, paid_amount_hp, status, idempotency_key, scheduled_at, created_at, updated_at)
                VALUES (?, ?, ?, 'LECTURE_ORDER_ITEM', ?, 'NORMAL', ?, 0, ?, ?, now(), now(), now())""",
                SELLER_ID, sellerWalletId, holdId(orderItemId), orderItemId, PRICE_HP, status,
                "SETTLEMENT:LECTURE_ORDER_ITEM:" + orderItemId);
    }

    // #150의 지급 호출 형태: 보류한 구매자 → 판매자, 원천 = 그 주문 항목, 남은 보류 전부
    private static PayoutCommand payout(User buyer, Long orderItemId, String payoutRequestId) {
        return PayoutCommand.full(buyer.getId(), ITEM, orderItemId, payoutRequestId, SELLER_ID, ITEM_SOURCE,
                orderItemId);
    }

    private User buyer(String key) {
        return memberFacade.oauthLogin(OAuthProvider.GOOGLE, key + "-sub", key + "@example.com", "강의구매자");
    }

    // 회원 지갑을 준비하고 충전 완료 건 + 같은 금액의 충전 원장으로 충전 HP 가용 잔액을 만든다(충전 API가 아직 없다).
    private Long fundCharge(Long userId, long amountHp) {
        Long walletId = walletFacade.getOrCreateWallet(userId).getId();
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "order-" + userId + "-" + sequence.incrementAndGet(),
                Timestamp.from(T1), Timestamp.from(T1));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
        return walletId;
    }

    // 수익 HP 가용 잔액을 늘리는 지급 원장을 기록한다.
    private Long fundRevenue(Long userId, long amountHp) {
        Long walletId = walletFacade.getOrCreateWallet(userId).getId();
        int id = sequence.incrementAndGet();
        ledgerRecordUseCase.record(new LedgerOperation("PAYOUT:TEST:" + id, LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, (long) id, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
        return walletId;
    }

    // 강의를 주문하고 응답의 data를 돌려준다. 강의 모듈의 회원 복제는 가입 이벤트로 비동기로 만들어지므로 성공할 때까지 기다린다.
    private Map<String, Object> order(User buyer, Long lectureId) {
        AtomicReference<String> body = new AtomicReference<>();
        await().untilAsserted(() -> body.set(mockMvc.perform(post("/api/v1/lectures/orders").with(csrf())
                        .with(TestLogin.as(buyer.getId(), Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\": [" + lectureId + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()));
        return JsonPath.read(body.get(), "$.data");
    }

    @SuppressWarnings("unchecked")
    private static Long orderItemId(Map<String, Object> order) {
        return ((Number) ((List<Map<String, Object>>) order.get("items")).getFirst().get("orderItemId")).longValue();
    }

    private static Long orderId(Map<String, Object> order) {
        return ((Number) order.get("orderId")).longValue();
    }

    private Lecture saveOnSaleLecture(Long sellerId) {
        LocalDateTime now = LocalDateTime.now();
        Lecture lecture = Lecture.register(sellerId, "방송 장비 세팅 강의", "처음 방송하는 사람을 위한 장비 세팅", PRICE_HP, null,
                List.of(new LectureContentSpec(101L, "1강 장비 고르기", ContentType.VIDEO)), now);
        lecture.approve(1L, now);
        return lectureRepository.save(lecture);
    }

    // 테이블에 행 단위 실패 트리거를 잠시 걸고 동작을 실행한 뒤 지운다.
    private void failOn(String table, ThrowingRunnable action) {
        jdbcTemplate.execute("""
                CREATE FUNCTION billing.test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test failure';
                END;
                $$""");
        jdbcTemplate.execute("CREATE TRIGGER trg_test_fail BEFORE INSERT ON billing." + table
                + " FOR EACH ROW EXECUTE FUNCTION billing.test_fail()");
        try {
            action.run();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER trg_test_fail ON billing." + table);
            jdbcTemplate.execute("DROP FUNCTION billing.test_fail()");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private List<String> holds() {
        return jdbcTemplate.queryForList("""
                SELECT idempotency_key || ' ' || (held_charge_hp + held_revenue_hp) || ' ' || status
                FROM billing.point_hold ORDER BY id""", String.class);
    }

    private Long holdId(Long orderItemId) {
        return jdbcTemplate.queryForObject("SELECT id FROM billing.point_hold WHERE idempotency_key = ?", Long.class,
                "HOLD:LECTURE_ORDER_ITEM:" + orderItemId);
    }

    private List<String> settlements() {
        return jdbcTemplate.queryForList("""
                SELECT idempotency_key || ' ' || receiver_user_id || ' ' || receiver_wallet_id || ' ' || source_type || ' '
                    || source_id || ' ' || payout_type || ' ' || gross_amount_hp || ' ' || paid_amount_hp || ' ' || status
                FROM billing.settlement ORDER BY id""", String.class);
    }

    private int settlementCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.settlement", Integer.class);
    }

    private List<String> ledgerLines(String operationKey) {
        return jdbcTemplate.queryForList("""
                SELECT wallet_id || ' ' || point_type || ' ' || balance_bucket || ' ' || amount_hp
                FROM billing.point_ledger WHERE operation_key = ? ORDER BY id""", String.class, operationKey);
    }

    private String itemStatus(Long orderItemId) {
        return jdbcTemplate.queryForObject("SELECT status FROM lectures.order_item WHERE id = ?", String.class,
                orderItemId);
    }

    private int sellerWalletCount(Long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.wallet WHERE user_id = ?", Integer.class,
                userId);
    }

    private Map<String, Object> wallet(Long walletId) {
        return jdbcTemplate.queryForMap("""
                SELECT charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet WHERE id = ?""", walletId);
    }

    // 지갑·보류·상세·배분·지급 건·원장 상태를 비교할 수 있게 모은다.
    private Map<String, Object> financialSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("wallets", jdbcTemplate.queryForList("""
                SELECT id, charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet ORDER BY id"""));
        snapshot.put("holds", jdbcTemplate.queryForList(
                "SELECT id, held_charge_hp, held_revenue_hp, status FROM billing.point_hold ORDER BY id"));
        snapshot.put("details", jdbcTemplate.queryForList("SELECT id FROM billing.point_hold_detail ORDER BY id"));
        snapshot.put("usages", jdbcTemplate.queryForList(
                "SELECT id, remaining_held_hp, consumed_hp, released_hp FROM billing.point_charge_usage ORDER BY id"));
        snapshot.put("settlements", jdbcTemplate.queryForList(
                "SELECT id, status, paid_amount_hp FROM billing.settlement ORDER BY id"));
        snapshot.put("ledgers", jdbcTemplate.queryForList("SELECT id FROM billing.point_ledger ORDER BY id"));
        return snapshot;
    }

    private static void assertBillingError(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo(code));
    }
}
