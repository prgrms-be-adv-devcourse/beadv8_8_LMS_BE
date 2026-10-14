package com.hapbang.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.lecture.app.OrderPayoutUseCase;
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

/**
 * 강의 주문·지급이 shared BillingPort로 실제 billing 모듈(BillingPortAdapter)을 호출하는지 확인한다.
 * 강의 모듈 테스트는 Fake billing을 쓰므로, 실제 빈 연결과 billing 예외가 강의 트랜잭션을 롤백하는지,
 * 지급 요청(요청 ID 형식·지급 원천)을 billing이 받아들이는지는 여기서 본다.
 * billing에 충전 API가 없어 충전은 billing 테스트와 같이 충전 건과 충전 원장을 직접 기록한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class LectureOrderBillingIntegrationTest {

    private static final Long SELLER_ID = 999_999L;
    private static final long PRICE_HP = 10_000L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    LectureRepository lectureRepository;

    @Autowired
    LectureOrderRepository lectureOrderRepository;

    @Autowired
    OrderPayoutUseCase orderPayoutUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 잔액이_없으면_실제_billing이_보류를_거절하고_주문이_남지_않는다() {
        User buyer = memberFacade.oauthLogin(OAuthProvider.GOOGLE, "lecture-order-billing-sub",
                "lecture-order-billing@example.com", "강의구매자");
        Long lectureId = saveOnSaleLecture().getId();
        long ordersBefore = lectureOrderRepository.count();

        // 지갑은 가입 이벤트로 비동기로 만들어진다. 지갑이 생기기 전에는 404 WALLET_NOT_FOUND다
        await().untilAsserted(() -> mockMvc.perform(post("/api/v1/lectures/orders").with(csrf())
                        .with(TestLogin.as(buyer.getId(), Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\": [" + lectureId + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE")));

        assertThat(lectureOrderRepository.count()).isEqualTo(ordersBefore);
    }

    @Test
    void 지급_시각이_지난_구매_확정_항목의_보류_HP가_판매자_수익_HP로_지급된다() throws Exception {
        User buyer = memberFacade.oauthLogin(OAuthProvider.GOOGLE, "lecture-payout-buyer-sub",
                "lecture-payout-buyer@example.com", "지급구매자");
        User seller = memberFacade.oauthLogin(OAuthProvider.GOOGLE, "lecture-payout-seller-sub",
                "lecture-payout-seller@example.com", "지급판매자");
        // 지갑은 가입 이벤트로 비동기로 만들어진다
        Wallet buyerWallet = await().until(() -> walletRepository.findByUserId(buyer.getId()).orElse(null),
                wallet -> wallet != null);
        await().until(() -> walletRepository.findByUserId(seller.getId()).isPresent());
        charge(buyerWallet.getId(), PRICE_HP);
        Long lectureId = saveOnSaleLecture(seller.getId()).getId();
        String body = mockMvc.perform(post("/api/v1/lectures/orders").with(csrf())
                        .with(TestLogin.as(buyer.getId(), Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\": [" + lectureId + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long orderItemId = JsonPath.parse(body).read("$.data.items[0].orderItemId", Long.class);
        // 구매 확정되고 지급 예정 시각이 지난 항목으로 만든다
        jdbcTemplate.update("""
                update lectures.order_item set status = 'CONFIRMED', confirmation_type = 'DIRECT',
                    confirmed_at = purchased_at, payout_scheduled_at = ? where id = ?""",
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)), orderItemId);

        orderPayoutUseCase.payOutDueItems();

        assertThat(jdbcTemplate.queryForObject("select status from lectures.order_item where id = ?", String.class,
                orderItemId)).isEqualTo("PAID_OUT");
        Wallet sellerWallet = walletRepository.findByUserId(seller.getId()).orElseThrow();
        assertThat(sellerWallet.getRevenueBalanceHp()).isEqualTo(PRICE_HP);
        Wallet buyerAfter = walletRepository.findByUserId(buyer.getId()).orElseThrow();
        assertThat(buyerAfter.getChargeBalanceHp()).isZero();
        assertThat(buyerAfter.getHeldChargeHp()).isZero();
    }

    // 충전 완료 건과 같은 금액의 충전 원장을 함께 만들어 충전 원금 합계와 지갑 충전 HP 잔액을 맞춘다 (billing 테스트와 같음)
    private void charge(Long walletId, long amountHp) {
        Instant chargedAt = Instant.now();
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "lecture-payout-" + walletId, Timestamp.from(chargedAt),
                Timestamp.from(chargedAt));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, chargedAt,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
    }

    private Lecture saveOnSaleLecture() {
        return saveOnSaleLecture(SELLER_ID);
    }

    private Lecture saveOnSaleLecture(Long sellerId) {
        LocalDateTime now = LocalDateTime.now();
        Lecture lecture = Lecture.register(sellerId, "방송 장비 세팅 강의", "처음 방송하는 사람을 위한 장비 세팅", 10_000L, null,
                List.of(new LectureContentSpec(101L, "1강 장비 고르기", ContentType.VIDEO)), now);
        lecture.approve(1L, now);
        return lectureRepository.save(lecture);
    }
}
