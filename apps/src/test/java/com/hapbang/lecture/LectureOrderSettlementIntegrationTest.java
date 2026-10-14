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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
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
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.port.BillingPort;
import com.hapbang.shared.billing.port.LectureSettlementPort;
import com.hapbang.shared.lecture.port.LectureObjectionPort;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

/**
 * 강의 주문·구매 취소·이의 제기 인정(#156)이 shared BillingPort·LectureSettlementPort로 실제 billing을 호출해, 강의 주문 상태와 billing의 보류·반환·지급 예정 건이
 * 한 트랜잭션으로 함께 커밋·롤백되는지 실제 PostgreSQL로 확인한다 (#182). 잔액은 billing에 충전 API가 없어 충전 건·충전 원장을 직접 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class LectureOrderSettlementIntegrationTest {

    private static final Long SELLER_ID = 999_998L;
    private static final Long SELLER_WITHOUT_WALLET_ID = 999_997L;
    private static final long PRICE_HP = 10_000L;
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");

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
    LectureSettlementPort lectureSettlementPort;

    @Autowired
    LectureObjectionPort lectureObjectionPort;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void shared_포트는_billing의_실제_Adapter로_주입된다() {
        assertThat(AopUtils.getTargetClass(billingPort).getName()).isEqualTo("com.hapbang.billing.in.BillingPortAdapter");
        assertThat(AopUtils.getTargetClass(lectureSettlementPort).getName())
                .isEqualTo("com.hapbang.billing.in.LectureSettlementPortAdapter");
    }

    @Test
    void 주문하면_보류와_판매자_지급_예정이_주문과_함께_커밋된다() throws Exception {
        User buyer = buyer("settlement-order");
        Long buyerWalletId = fund(buyer.getId(), PRICE_HP);
        Long sellerWalletId = walletFacade.getOrCreateWallet(SELLER_ID).getId();
        Long lectureId = saveOnSaleLecture(SELLER_ID).getId();

        Map<String, Object> order = order(buyer, lectureId);

        Long orderItemId = orderItemId(order);
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
        Map<String, Object> settlement = settlement(orderItemId);
        assertThat(settlement).containsEntry("status", "SCHEDULED")
                .containsEntry("receiver_user_id", SELLER_ID)
                .containsEntry("receiver_wallet_id", sellerWalletId)
                .containsEntry("gross_amount_hp", PRICE_HP)
                .containsEntry("paid_amount_hp", 0L);
        // billing의 지급 가능 시각 = 강의의 지급 예정 시각(구매일 + 31일 00:00 KST)
        LocalDateTime payoutScheduledAt = jdbcTemplate.queryForObject(
                "SELECT payout_scheduled_at FROM lectures.order_item WHERE id = ?", LocalDateTime.class, orderItemId);
        assertThat(((Timestamp) settlement.get("scheduled_at")).toInstant())
                .isEqualTo(payoutScheduledAt.atZone(LectureConfig.ZONE).toInstant());
        assertThat(availableChargeHp(buyerWalletId)).isZero();
        assertThat(availableRevenueHp(sellerWalletId)).isZero();
    }

    @Test
    void 판매자_지갑이_없어_지급_예정_등록이_실패하면_주문과_보류가_모두_롤백된다() {
        User buyer = buyer("settlement-no-seller-wallet");
        Long buyerWalletId = fund(buyer.getId(), PRICE_HP);
        Long lectureId = saveOnSaleLecture(SELLER_WITHOUT_WALLET_ID).getId();
        long ordersBefore = lectureOrderRepository.count();

        await().untilAsserted(() -> mockMvc.perform(post("/api/v1/lectures/orders").with(csrf())
                        .with(TestLogin.as(buyer.getId(), Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\": [" + lectureId + "]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND")));

        assertThat(lectureOrderRepository.count()).isEqualTo(ordersBefore);
        assertThat(holds()).isEmpty();
        assertThat(settlementCount()).isZero();
        assertThat(availableChargeHp(buyerWalletId)).isEqualTo(PRICE_HP);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.wallet WHERE user_id = ?", Integer.class,
                SELLER_WITHOUT_WALLET_ID)).isZero();
    }

    @Test
    void 구매_취소하면_보류_반환과_지급_예정_취소가_구매_취소와_함께_커밋된다() throws Exception {
        User buyer = buyer("settlement-cancel");
        Long buyerWalletId = fund(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Map<String, Object> order = order(buyer, saveOnSaleLecture(SELLER_ID).getId());
        Long orderItemId = orderItemId(order);

        mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId(order),
                        orderItemId).with(csrf()).with(TestLogin.as(buyer.getId(), Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 RELEASED");
        assertThat(settlement(orderItemId)).containsEntry("status", "CANCELLED");
        assertThat(availableChargeHp(buyerWalletId)).isEqualTo(PRICE_HP);
        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_key FROM billing.point_hold_detail ORDER BY id", String.class))
                .containsExactly("RELEASE:" + holdId(orderItemId) + ":LECTURE-CANCEL-" + orderItemId);
    }

    @Test
    void 이의_제기를_인정하면_보류_반환과_지급_예정_취소가_항목_반환과_함께_커밋된다() throws Exception {
        User buyer = buyer("settlement-objection-accept");
        Long buyerWalletId = fund(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));

        // 고객센터처럼 문의 저장과 같은 트랜잭션에서 포트를 부른다
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                lectureObjectionPort.fileObjection(orderItemId, buyer.getId(), 1L, "강의 소개와 영상 내용이 다릅니다."));
        assertThat(settlement(orderItemId)).containsEntry("status", "SCHEDULED");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                lectureObjectionPort.resolveObjection(orderItemId, 1L, true, 1L));

        assertThat(jdbcTemplate.queryForObject("SELECT status || ' ' || objection_status FROM lectures.order_item "
                + "WHERE id = ?", String.class, orderItemId)).isEqualTo("RETURNED ACCEPTED");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " 0 RELEASED");
        assertThat(settlement(orderItemId)).containsEntry("status", "CANCELLED");
        assertThat(availableChargeHp(buyerWalletId)).isEqualTo(PRICE_HP);
        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_key FROM billing.point_hold_detail ORDER BY id", String.class))
                .containsExactly("RELEASE:" + holdId(orderItemId) + ":LECTURE-RETURN-" + orderItemId);
    }

    @Test
    void 지급_예정_취소가_실패하면_반환과_이의_제기_인정이_모두_롤백된다() throws Exception {
        User buyer = buyer("settlement-objection-cancel-fail");
        Long buyerWalletId = fund(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Long orderItemId = orderItemId(order(buyer, saveOnSaleLecture(SELLER_ID).getId()));
        lectureObjectionPort.fileObjection(orderItemId, buyer.getId(), 1L, "강의 소개와 영상 내용이 다릅니다.");
        // 취소할 수 없는 지급 건 상태(지급 실패)를 Fixture로 만든다
        jdbcTemplate.update("UPDATE billing.settlement SET status = 'FAILED', failed_at = now() WHERE source_id = ?",
                orderItemId);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                lectureObjectionPort.resolveObjection(orderItemId, 1L, true, 1L)))
                .isInstanceOf(BillingException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT status || ' ' || objection_status FROM lectures.order_item "
                + "WHERE id = ?", String.class, orderItemId)).isEqualTo("PURCHASED FILED");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Integer.class))
                .isZero();
        assertThat(availableChargeHp(buyerWalletId)).isZero();
    }

    @Test
    void 지급_예정_취소가_실패하면_반환과_구매_취소가_모두_롤백된다() throws Exception {
        User buyer = buyer("settlement-cancel-fail");
        Long buyerWalletId = fund(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Map<String, Object> order = order(buyer, saveOnSaleLecture(SELLER_ID).getId());
        Long orderItemId = orderItemId(order);
        // 취소할 수 없는 지급 건 상태(지급 실패)를 Fixture로 만든다. 실제 지급 실패 처리는 후속 Issue다.
        jdbcTemplate.update("UPDATE billing.settlement SET status = 'FAILED', failed_at = now() WHERE source_id = ?",
                orderItemId);

        mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId(order),
                        orderItemId).with(csrf()).with(TestLogin.as(buyer.getId(), Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_INVALID_STATE"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM lectures.order_item WHERE id = ?", String.class,
                orderItemId)).isEqualTo("PURCHASED");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Integer.class))
                .isZero();
        assertThat(settlement(orderItemId)).containsEntry("status", "FAILED");
        assertThat(availableChargeHp(buyerWalletId)).isZero();
    }

    @Test
    void 반환이_실패하면_지급_예정_취소는_실행되지_않고_구매_취소가_모두_롤백된다() throws Exception {
        User buyer = buyer("settlement-release-fail");
        Long buyerWalletId = fund(buyer.getId(), PRICE_HP);
        walletFacade.getOrCreateWallet(SELLER_ID);
        Map<String, Object> order = order(buyer, saveOnSaleLecture(SELLER_ID).getId());
        Long orderItemId = orderItemId(order);
        // billing 반환 상세 저장이 실패하게 행 트리거를 잠시 건다.
        jdbcTemplate.execute("""
                CREATE FUNCTION billing.test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test failure';
                END;
                $$""");
        jdbcTemplate.execute("CREATE TRIGGER trg_test_fail BEFORE INSERT ON billing.point_hold_detail "
                + "FOR EACH ROW EXECUTE FUNCTION billing.test_fail()");
        try {
            mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId(order),
                            orderItemId).with(csrf()).with(TestLogin.as(buyer.getId(), Role.USER)))
                    .andExpect(status().isInternalServerError());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER trg_test_fail ON billing.point_hold_detail");
            jdbcTemplate.execute("DROP FUNCTION billing.test_fail()");
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM lectures.order_item WHERE id = ?", String.class,
                orderItemId)).isEqualTo("PURCHASED");
        assertThat(holds()).containsExactly("HOLD:LECTURE_ORDER_ITEM:" + orderItemId + " " + PRICE_HP + " HELD");
        assertThat(settlement(orderItemId)).containsEntry("status", "SCHEDULED");
        assertThat(availableChargeHp(buyerWalletId)).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private User buyer(String key) {
        return memberFacade.oauthLogin(OAuthProvider.GOOGLE, key + "-sub", key + "@example.com", "강의구매자");
    }

    // 회원 지갑을 준비하고 충전 완료 건 + 같은 금액의 충전 원장으로 충전 HP 가용 잔액을 만든다(충전 API가 아직 없다).
    private Long fund(Long userId, long amountHp) {
        Long walletId = walletFacade.getOrCreateWallet(userId).getId();
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "order-" + userId, Timestamp.from(T1), Timestamp.from(T1));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
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

    private List<String> holds() {
        return jdbcTemplate.queryForList("""
                SELECT idempotency_key || ' ' || (held_charge_hp + held_revenue_hp) || ' ' || status
                FROM billing.point_hold ORDER BY id""", String.class);
    }

    private Long holdId(Long orderItemId) {
        return jdbcTemplate.queryForObject("SELECT id FROM billing.point_hold WHERE idempotency_key = ?", Long.class,
                "HOLD:LECTURE_ORDER_ITEM:" + orderItemId);
    }

    private Map<String, Object> settlement(Long orderItemId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, receiver_user_id, receiver_wallet_id, gross_amount_hp, paid_amount_hp, scheduled_at
                FROM billing.settlement WHERE source_type = 'LECTURE_ORDER_ITEM' AND source_id = ?""", orderItemId);
    }

    private int settlementCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.settlement", Integer.class);
    }

    private long availableChargeHp(Long walletId) {
        return jdbcTemplate.queryForObject("SELECT charge_balance_hp FROM billing.wallet WHERE id = ?", Long.class,
                walletId);
    }

    private long availableRevenueHp(Long walletId) {
        return jdbcTemplate.queryForObject("SELECT revenue_balance_hp FROM billing.wallet WHERE id = ?", Long.class,
                walletId);
    }
}
