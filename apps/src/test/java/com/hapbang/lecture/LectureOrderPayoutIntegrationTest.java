package com.hapbang.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import com.hapbang.lecture.app.LectureOrderSupport;
import com.hapbang.lecture.app.OrderPayoutUseCase;
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
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
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;
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
 *     <li>지급은 강의(#150)가 지급 시점·이의 제기를 판단한 뒤 부른다. billing 규칙은 #150의 호출 형태
 *     ({@code PayoutCommand.full(구매자, LECTURE_ORDER_ITEM, 항목 ID, "LECTURE-PAYOUT-{항목 ID}", 판매자,
 *     LECTURE_ORDER_ITEM, 항목 ID)})로 billing을 직접 불러 보고, 강의 상태 전이와 함께 커밋·롤백되는지는 실제 지급 배치
 *     ({@link OrderPayoutUseCase})로 본다.</li>
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

    @Autowired
    OrderPayoutUseCase orderPayoutUseCase;

    // 지급 배치가 항목을 읽은 직후에 멈추게 해 그 사이에 이의 제기 접수·인정을 커밋한다 (스파이는 실제 메서드를 부른다)
    @MockitoSpyBean
    LectureOrderSupport lectureOrderSupport;

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
    // 실제 지급 배치(#150) + 실제 billing: 항목 상태 전이와 지급이 함께 커밋·롤백된다
    // ---------------------------------------------------------------------------------------------

    @Test
    void 지급_배치는_판매자_지갑이_없으면_항목을_CONFIRMED로_두고_지갑이_생긴_뒤_다음_실행에서_같은_요청_ID로_지급한다() {
        User buyer = buyer("batch-seller-wallet-later");
        Long buyerWalletId = fundCharge(buyer.getId(), PRICE_HP);
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_WITHOUT_WALLET_ID).getId()));
        makePayoutDue(orderItemId);
        Map<String, Object> before = financialSnapshot();

        // WALLET_NOT_FOUND는 배치가 항목별로 잡고 넘어간다. 항목 상태 변경도 billing 변경도 남지 않는다
        orderPayoutUseCase.payOutDueItems();

        assertThat(itemStatus(orderItemId)).isEqualTo("CONFIRMED");
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(sellerWalletCount(SELLER_WITHOUT_WALLET_ID)).isZero();

        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_WITHOUT_WALLET_ID).getId();
        orderPayoutUseCase.payOutDueItems();

        String key = "PAYOUT:" + holdId(orderItemId) + ":LECTURE-PAYOUT-" + orderItemId;
        assertThat(itemStatus(orderItemId)).isEqualTo("PAID_OUT");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 PAID");
        assertThat(settlements()).containsExactly(key + " " + SELLER_WITHOUT_WALLET_ID + " " + sellerWalletId
                + " LECTURE_ORDER_ITEM " + orderItemId + " NORMAL 10000 10000 COMPLETED");
        assertThat(ledgerLines(key)).containsExactly(
                buyerWalletId + " CHARGE HELD -10000",
                sellerWalletId + " REVENUE AVAILABLE 10000");
        assertThat(wallet(sellerWalletId)).containsEntry("revenue_balance_hp", PRICE_HP);

        // 이미 지급한 항목은 다음 실행의 대상이 아니다
        Map<String, Object> paid = financialSnapshot();
        orderPayoutUseCase.payOutDueItems();
        assertThat(financialSnapshot()).isEqualTo(paid);
    }

    @Test
    void 이의_제기가_기각된_항목은_지급_예정_시각이_지나면_지급_배치가_실제로_지급한다() {
        User buyer = buyer("batch-objection-rejected");
        fundCharge(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        lectureObjectionPort.fileObjection(orderItemId, buyer.getId(), 1L, "강의 소개와 영상 내용이 다릅니다.");
        makePayoutDue(orderItemId);

        // 접수 중에는 지급 예정 시각이 지나도 지급하지 않는다
        Map<String, Object> filed = financialSnapshot();
        orderPayoutUseCase.payOutDueItems();
        assertThat(itemStatusAndObjection(orderItemId)).isEqualTo("CONFIRMED FILED");
        assertThat(financialSnapshot()).isEqualTo(filed);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                lectureObjectionPort.resolveObjection(orderItemId, 1L, false, 1L));
        orderPayoutUseCase.payOutDueItems();

        assertThat(itemStatusAndObjection(orderItemId)).isEqualTo("PAID_OUT REJECTED");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 PAID");
        assertThat(settlementCount()).isEqualTo(1);
        assertThat(wallet(sellerWalletId)).containsEntry("revenue_balance_hp", PRICE_HP);
    }

    @Test
    void 지급_배치가_항목을_읽은_뒤_이의_제기_접수가_커밋_전이면_배치는_항목_행_락을_기다렸다가_낙관적_락으로_롤백된다()
            throws Exception {
        User buyer = buyer("batch-filing-race");
        fundCharge(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        makePayoutDue(orderItemId);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resumeBatch = new CountDownLatch(1);
        pauseBatchAfterRead(orderItemId, loaded, resumeBatch);
        CountDownLatch filed = new CountDownLatch(1);
        CountDownLatch commitFiling = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> batch = executor.submit(() -> orderPayoutUseCase.payOutDueItems());
            assertThat(loaded.await(30, TimeUnit.SECONDS)).isTrue();
            // 지급 예정 시각 직전에 시작한 접수를 재현한다. 접수는 기한 전에만 되므로 기한만 뒤로 미루고(version은 그대로)
            // 실제 접수 포트로 접수한다. 접수 트랜잭션은 flush로 항목 행을 잠근 채 커밋하지 않고 기다린다
            jdbcTemplate.update("UPDATE lectures.order_item SET payout_scheduled_at = ? WHERE id = ?",
                    Timestamp.valueOf(LocalDateTime.now().plusDays(1)), orderItemId);
            Future<?> filing = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        lectureObjectionPort.fileObjection(orderItemId, buyer.getId(), 1L, "강의 소개와 영상 내용이 다릅니다.");
                        filed.countDown();
                        awaitLatch(commitFiling);
                    }));
            assertThat(filed.await(30, TimeUnit.SECONDS)).isTrue();
            resumeBatch.countDown();
            // 배치의 PAID_OUT UPDATE가 접수 트랜잭션의 항목 행 락을 기다린다(billing 호출 전)
            awaitLockWaiters(1);
            commitFiling.countDown();
            filing.get(30, TimeUnit.SECONDS);
            batch.get(30, TimeUnit.SECONDS);
        }

        // 접수가 커밋되면 배치의 UPDATE는 version이 달라 0행이 되어 롤백되고, billing을 부르지 않는다
        assertThat(itemStatusAndObjection(orderItemId)).isEqualTo("CONFIRMED FILED");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Integer.class))
                .isZero();
        assertThat(settlementCount()).isZero();
        assertThat(wallet(sellerWalletId)).containsEntry("revenue_balance_hp", 0L);
    }

    @Test
    void 지급_배치가_항목을_읽은_뒤_이의_제기_인정이_반환까지_마치고_커밋_전이어도_배치는_기다리지_않고_롤백되어_billing을_부르지_않는다()
            throws Exception {
        User buyer = buyer("batch-objection-race");
        Long buyerWalletId = fundCharge(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        makePayoutDue(orderItemId);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resumeBatch = new CountDownLatch(1);
        pauseBatchAfterRead(orderItemId, loaded, resumeBatch);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch commitAccept = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> batch = executor.submit(() -> orderPayoutUseCase.payOutDueItems());
            assertThat(loaded.await(30, TimeUnit.SECONDS)).isTrue();
            // 지급 예정 시각 직전에 시작한 접수가 지금 커밋된 것과 같다(접수 API는 기한 뒤 접수를 거부하므로 행을 직접 바꾼다)
            jdbcTemplate.update("""
                    UPDATE lectures.order_item SET objection_status = 'FILED', objection_inquiry_id = 1,
                        objection_reason = '강의 소개와 영상 내용이 다릅니다.', objection_filed_at = now(),
                        version = version + 1
                    WHERE id = ?""", orderItemId);
            // 인정 트랜잭션은 주문 → 항목 → 구매자 지갑 → 보류를 잠그고 반환까지 마친 뒤 커밋하지 않고 기다린다
            Future<?> accept = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        lectureObjectionPort.resolveObjection(orderItemId, 1L, true, 1L);
                        released.countDown();
                        awaitLatch(commitAccept);
                    }));
            assertThat(released.await(30, TimeUnit.SECONDS)).isTrue();
            resumeBatch.countDown();
            // 커밋된 행의 version이 이미 접수로 바뀌어 배치의 UPDATE ... WHERE version = (읽은 값)은 인정 트랜잭션의 락을
            // 기다리지 않고 0행으로 끝난다. 인정이 커밋되기 전에 배치가 끝나므로 배치와 인정 사이에 대기 관계가 없다
            batch.get(30, TimeUnit.SECONDS);
            assertThat(accept.isDone()).isFalse();
            commitAccept.countDown();
            accept.get(30, TimeUnit.SECONDS);
        }

        // 배치의 PAID_OUT 변경은 billing 호출 전 flush에서 version 충돌로 실패해 지급을 부르지 않는다
        assertThat(itemStatusAndObjection(orderItemId)).isEqualTo("RETURNED ACCEPTED");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 RELEASED");
        assertThat(jdbcTemplate.queryForList("SELECT action_type FROM billing.point_hold_detail", String.class))
                .containsExactly("RELEASE");
        assertThat(settlementCount()).isZero();
        assertThat(wallet(buyerWalletId)).containsEntry("charge_balance_hp", PRICE_HP)
                .containsEntry("held_charge_hp", 0L);
        assertThat(wallet(sellerWalletId)).containsEntry("revenue_balance_hp", 0L);
        // 원장 합: 구매자 충전 +10,000만 남는다(보류·반환은 서로 상쇄, 지급 원장 없음)
        assertThat(jdbcTemplate.queryForObject("SELECT sum(amount_hp) FROM billing.point_ledger", Long.class))
                .isEqualTo(PRICE_HP);

        // 반환이 먼저 커밋된 항목은 다음 배치 실행에서도 대상이 아니다(재시도해도 지급하지 않는다)
        Map<String, Object> returned = financialSnapshot();
        orderPayoutUseCase.payOutDueItems();
        assertThat(itemStatusAndObjection(orderItemId)).isEqualTo("RETURNED ACCEPTED");
        assertThat(financialSnapshot()).isEqualTo(returned);
    }

    @Test
    void 같은_주문의_한_항목_지급과_다른_항목_구매_취소가_구매자_지갑_락에서_만나도_교착_없이_모두_처리된다() throws Exception {
        // 같은 항목의 지급(CONFIRMED)과 구매 취소(PURCHASED)는 상태가 겹치지 않는다. 겹치는 금융 자원은 구매자 지갑이다
        User buyer = buyer("batch-cancel-race");
        Long buyerWalletId = fundCharge(buyer.getId(), 2 * PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Map<String, Object> order = order(buyer, List.of(saveOnSaleLecture(SELLER_ID).getId(),
                saveOnSaleLecture(SELLER_ID).getId()));
        Long payItemId = itemIdAt(order, 0);
        Long cancelItemId = itemIdAt(order, 1);
        makePayoutDue(payItemId);
        CountDownLatch walletLocked = new CountDownLatch(1);
        CountDownLatch releaseWallet = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            // 구매자 지갑을 먼저 잡아 두 트랜잭션이 각자 앞 단계 락(지급: 항목 A, 취소: 주문·항목 B)을 쥔 채 지갑에서 만나게 한다
            Future<?> blocker = executor.submit(() -> holdRowLock("SELECT id FROM billing.wallet WHERE id = ?",
                    buyerWalletId, walletLocked, releaseWallet));
            assertThat(walletLocked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> batch = executor.submit(() -> orderPayoutUseCase.payOutDueItems());
            Future<Integer> cancel = executor.submit(() -> cancelItem(buyer, orderId(order), cancelItemId));
            awaitLockWaiters(2);
            releaseWallet.countDown();
            blocker.get(30, TimeUnit.SECONDS);
            batch.get(30, TimeUnit.SECONDS);
            assertThat(cancel.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }

        assertThat(itemStatus(payItemId)).isEqualTo("PAID_OUT");
        assertThat(itemStatus(cancelItemId)).isEqualTo("CANCELED");
        assertThat(holds()).containsExactlyInAnyOrder(
                "HOLD:LECTURE_ORDER_ITEM:" + payItemId + " 0 PAID",
                "HOLD:LECTURE_ORDER_ITEM:" + cancelItemId + " 0 RELEASED");
        assertThat(settlementCount()).isEqualTo(1);
        assertThat(wallet(buyerWalletId)).containsEntry("charge_balance_hp", PRICE_HP)
                .containsEntry("held_charge_hp", 0L);
        assertThat(wallet(sellerWalletId)).containsEntry("revenue_balance_hp", PRICE_HP);
        // 원장 합 = 충전 20,000 (지급분은 판매자 수익 HP로 옮겨졌을 뿐 총량은 같다)
        assertThat(jdbcTemplate.queryForObject("SELECT sum(amount_hp) FROM billing.point_ledger", Long.class))
                .isEqualTo(2 * PRICE_HP);
    }

    @Test
    void 같은_주문의_이의_제기_인정과_다른_항목_구매_취소는_주문_락으로_직렬화되어_주문이_취소로_닫힌다() throws Exception {
        User buyer = buyer("accept-cancel-race");
        Long buyerWalletId = fundCharge(buyer.getId(), 2 * PRICE_HP);
        Map<String, Object> order = order(buyer, List.of(saveOnSaleLecture(SELLER_ID).getId(),
                saveOnSaleLecture(SELLER_ID).getId()));
        Long acceptItemId = itemIdAt(order, 0);
        Long cancelItemId = itemIdAt(order, 1);
        lectureObjectionPort.fileObjection(acceptItemId, buyer.getId(), 1L, "강의 소개와 영상 내용이 다릅니다.");
        CountDownLatch orderLocked = new CountDownLatch(1);
        CountDownLatch releaseOrder = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> blocker = executor.submit(() -> holdRowLock("SELECT id FROM lectures.lecture_order WHERE id = ?",
                    orderId(order), orderLocked, releaseOrder));
            assertThat(orderLocked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> accept = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> lectureObjectionPort.resolveObjection(acceptItemId, 1L, true, 1L)));
            Future<Integer> cancel = executor.submit(() -> cancelItem(buyer, orderId(order), cancelItemId));
            // 둘 다 주문 행 락에서 기다린다(항목·지갑 락을 잡기 전)
            awaitLockWaiters(2);
            releaseOrder.countDown();
            blocker.get(30, TimeUnit.SECONDS);
            accept.get(30, TimeUnit.SECONDS);
            assertThat(cancel.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }

        // 늦은 쪽이 앞 변경의 커밋을 보고 마지막 항목으로 판정해 주문을 닫는다
        assertThat(itemStatusAndObjection(acceptItemId)).isEqualTo("RETURNED ACCEPTED");
        assertThat(itemStatus(cancelItemId)).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM lectures.lecture_order WHERE id = ?", String.class,
                orderId(order))).isEqualTo("CANCELED");
        assertThat(holds()).containsExactlyInAnyOrder(
                "HOLD:LECTURE_ORDER_ITEM:" + acceptItemId + " 0 RELEASED",
                "HOLD:LECTURE_ORDER_ITEM:" + cancelItemId + " 0 RELEASED");
        assertThat(settlementCount()).isZero();
        assertThat(wallet(buyerWalletId)).containsEntry("charge_balance_hp", 2 * PRICE_HP)
                .containsEntry("held_charge_hp", 0L);
    }

    @Test
    void 같은_항목의_이의_제기_인정이_동시에_두_번_와도_반환은_한_번이고_늦은_쪽은_NOT_FILED로_롤백된다() throws Exception {
        User buyer = buyer("double-accept-race");
        Long buyerWalletId = fundCharge(buyer.getId(), PRICE_HP);
        Map<String, Object> order = order(buyer, saveOnSaleLecture(SELLER_ID).getId());
        Long orderItemId = orderItemId(order);
        lectureObjectionPort.fileObjection(orderItemId, buyer.getId(), 1L, "강의 소개와 영상 내용이 다릅니다.");
        CountDownLatch orderLocked = new CountDownLatch(1);
        CountDownLatch releaseOrder = new CountDownLatch(1);
        List<Future<String>> accepts;

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> blocker = executor.submit(() -> holdRowLock("SELECT id FROM lectures.lecture_order WHERE id = ?",
                    orderId(order), orderLocked, releaseOrder));
            assertThat(orderLocked.await(30, TimeUnit.SECONDS)).isTrue();
            accepts = List.of(executor.submit(() -> acceptOutcome(orderItemId)),
                    executor.submit(() -> acceptOutcome(orderItemId)));
            awaitLockWaiters(2);
            releaseOrder.countDown();
            blocker.get(30, TimeUnit.SECONDS);
            assertThat(List.of(accepts.get(0).get(30, TimeUnit.SECONDS), accepts.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED", "NOT_FILED");
        }

        assertThat(itemStatusAndObjection(orderItemId)).isEqualTo("RETURNED ACCEPTED");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 RELEASED");
        assertThat(jdbcTemplate.queryForList("SELECT action_type FROM billing.point_hold_detail", String.class))
                .containsExactly("RELEASE");
        assertThat(wallet(buyerWalletId)).containsEntry("charge_balance_hp", PRICE_HP)
                .containsEntry("held_charge_hp", 0L);
    }

    // 지급 배치가 이 항목의 주문·항목을 읽은 직후 멈추게 한다(항목은 지연 로딩이라 여기서 읽어 둔다)
    private void pauseBatchAfterRead(Long orderItemId, CountDownLatch loaded, CountDownLatch resume) {
        doAnswer(invocation -> {
            LectureOrder order = (LectureOrder) invocation.callRealMethod();
            order.getItems();
            loaded.countDown();
            assertThat(resume.await(30, TimeUnit.SECONDS)).isTrue();
            return order;
        }).when(lectureOrderSupport).findOrderByItemId(eq(orderItemId));
    }

    // 한 행을 FOR UPDATE로 잠근 트랜잭션을 열어 두고, 풀어 줄 때까지 기다렸다가 커밋한다
    private void holdRowLock(String select, Long id, CountDownLatch locked, CountDownLatch release) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.queryForList(select + " FOR UPDATE", id);
            locked.countDown();
            awaitLatch(release);
        });
    }

    // 다른 트랜잭션의 락을 기다리는(아직 얻지 못한) 세션이 n개 이상이 될 때까지 기다린다
    private void awaitLockWaiters(int sessions) {
        await().atMost(30, TimeUnit.SECONDS).until(() -> jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted", Integer.class) >= sessions);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private int cancelItem(User buyer, Long orderId, Long orderItemId) throws Exception {
        return mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId,
                        orderItemId).with(csrf()).with(TestLogin.as(buyer.getId(), Role.USER)))
                .andReturn().getResponse().getStatus();
    }

    // 이의 제기 인정을 한 트랜잭션에서 시도하고 결과(성공 ACCEPTED 또는 거절 사유)를 돌려준다
    private String acceptOutcome(Long orderItemId) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    lectureObjectionPort.resolveObjection(orderItemId, 1L, true, 1L));
            return "ACCEPTED";
        } catch (LectureObjectionRefusedException exception) {
            return exception.getReason().name();
        }
    }

    @SuppressWarnings("unchecked")
    private static Long itemIdAt(Map<String, Object> order, int index) {
        return ((Number) ((List<Map<String, Object>>) order.get("items")).get(index).get("orderItemId")).longValue();
    }

    @Test
    void 지급_배치가_동시에_두_번_실행되어도_같은_항목은_한_번만_지급된다() throws Exception {
        User buyer = buyer("batch-double-run");
        Long buyerWalletId = fundCharge(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        makePayoutDue(orderItemId);
        // 두 실행이 모두 지급 전 항목을 읽은 뒤에 진행하게 한다(같은 version을 본 두 트랜잭션)
        CountDownLatch bothLoaded = new CountDownLatch(2);
        doAnswer(invocation -> {
            LectureOrder order = (LectureOrder) invocation.callRealMethod();
            order.getItems();
            bothLoaded.countDown();
            assertThat(bothLoaded.await(30, TimeUnit.SECONDS)).isTrue();
            return order;
        }).when(lectureOrderSupport).findOrderByItemId(eq(orderItemId));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> orderPayoutUseCase.payOutDueItems());
            Future<?> second = executor.submit(() -> orderPayoutUseCase.payOutDueItems());
            // 늦은 쪽은 항목 행 락을 기다렸다가 version 충돌로 끝난다(교착이면 시간 초과)
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        }

        String key = "PAYOUT:" + holdId(orderItemId) + ":LECTURE-PAYOUT-" + orderItemId;
        assertThat(itemStatus(orderItemId)).isEqualTo("PAID_OUT");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 PAID");
        assertThat(jdbcTemplate.queryForList("SELECT action_type FROM billing.point_hold_detail", String.class))
                .containsExactly("PAYOUT");
        assertThat(settlementCount()).isEqualTo(1);
        assertThat(ledgerLines(key)).hasSize(2);
        assertThat(wallet(sellerWalletId)).containsEntry("revenue_balance_hp", PRICE_HP);
        assertThat(wallet(buyerWalletId)).containsEntry("charge_balance_hp", 0L).containsEntry("held_charge_hp", 0L);
    }

    @Test
    void 지급_배치는_billing의_원천_중복_오류를_지급_완료로_보지_않고_항목을_CONFIRMED로_남긴다() {
        // #142·#182 방식의 지급 예정 건(SCHEDULED)이 남은 항목: 실행할 때마다 PAYOUT_ALREADY_SETTLED로 실패한다
        User buyer = buyer("batch-legacy-scheduled");
        fundCharge(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        insertLegacySettlement(orderItemId, sellerWalletId, "SCHEDULED");
        makePayoutDue(orderItemId);
        Map<String, Object> before = financialSnapshot();

        orderPayoutUseCase.payOutDueItems();
        orderPayoutUseCase.payOutDueItems();

        assertThat(itemStatus(orderItemId)).isEqualTo("CONFIRMED");
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
    }

    // 구매 확정되고 지급 예정 시각이 지난 항목으로 만든다(배치 조회 조건, OrderItem.isPayable)
    private void makePayoutDue(Long orderItemId) {
        jdbcTemplate.update("""
                UPDATE lectures.order_item SET status = 'CONFIRMED', confirmation_type = 'DIRECT',
                    confirmed_at = purchased_at, payout_scheduled_at = ? WHERE id = ?""",
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)), orderItemId);
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
        return order(buyer, List.of(lectureId));
    }

    // 여러 강의를 한 주문으로 산다(항목 순서 = 요청 순서)
    private Map<String, Object> order(User buyer, List<Long> lectureIds) {
        String ids = String.join(", ", lectureIds.stream().map(String::valueOf).toList());
        AtomicReference<String> body = new AtomicReference<>();
        await().untilAsserted(() -> body.set(mockMvc.perform(post("/api/v1/lectures/orders").with(csrf())
                        .with(TestLogin.as(buyer.getId(), Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\": [" + ids + "]}"))
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
