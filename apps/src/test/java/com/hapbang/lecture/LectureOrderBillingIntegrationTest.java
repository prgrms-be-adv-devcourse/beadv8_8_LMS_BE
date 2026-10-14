package com.hapbang.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.lecture.port.LectureObjectionPort;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;

/**
 * 강의 주문이 shared BillingPort로 실제 billing 모듈(BillingPortAdapter)을 호출하는지 확인한다.
 * 강의 모듈 테스트는 Fake billing을 쓰므로, 실제 빈 연결과 billing 예외가 강의 트랜잭션을 롤백하는지는 여기서 본다.
 * 충전 API가 아직 없어서, 잔액이 필요한 경로는 billing 원장 기록(LedgerRecordUseCase)으로 수익 HP를 넣어 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class LectureOrderBillingIntegrationTest {

    private static final Long SELLER_ID = 999_999L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    LectureRepository lectureRepository;

    @Autowired
    LectureOrderRepository lectureOrderRepository;

    @Autowired
    LectureObjectionPort lectureObjectionPort;

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
    void 이의_제기를_인정하면_실제_billing이_그_항목의_보류_HP를_차감했던_종류대로_반환한다() throws Exception {
        User buyer = memberFacade.oauthLogin(OAuthProvider.GOOGLE, "lecture-objection-billing-sub",
                "lecture-objection-billing@example.com", "이의제기구매자");
        // 지갑은 가입 이벤트로 비동기로 만들어진다
        Long walletId = await().until(() -> walletId(buyer.getId()), Objects::nonNull);
        addRevenue(walletId, 10_000L);
        Long lectureId = saveOnSaleLecture().getId();
        mockMvc.perform(post("/api/v1/lectures/orders").with(csrf())
                        .with(TestLogin.as(buyer.getId(), Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\": [" + lectureId + "]}"))
                .andExpect(status().isCreated());
        Long orderItemId = jdbcTemplate.queryForObject(
                "select id from lectures.order_item where buyer_user_id = ?", Long.class, buyer.getId());
        assertThat(wallet(walletId)).containsEntry("revenue_balance_hp", 0L).containsEntry("held_revenue_hp", 10_000L);

        lectureObjectionPort.fileObjection(orderItemId, buyer.getId(), 1L, "강의 소개와 영상 내용이 다릅니다.");
        lectureObjectionPort.resolveObjection(orderItemId, 1L, true, 1L);

        assertThat(wallet(walletId)).containsEntry("revenue_balance_hp", 10_000L).containsEntry("held_revenue_hp", 0L)
                .containsEntry("charge_balance_hp", 0L);
        assertThat(jdbcTemplate.queryForObject("select status from lectures.order_item where id = ?", String.class,
                orderItemId)).isEqualTo("RETURNED");
        assertThat(jdbcTemplate.queryForObject("select status from billing.point_hold "
                + "where reference_type = 'LECTURE_ORDER_ITEM' and reference_id = ?", String.class, orderItemId))
                .isEqualTo("RELEASED");
    }

    private Long walletId(Long userId) {
        return jdbcTemplate.queryForList("select id from billing.wallet where user_id = ?", Long.class, userId)
                .stream().findFirst().orElse(null);
    }

    private Map<String, Object> wallet(Long walletId) {
        return jdbcTemplate.queryForMap("select charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp "
                + "from billing.wallet where id = ?", walletId);
    }

    // 충전 API가 없어 지급 원장으로 수익 HP를 넣는다
    private void addRevenue(Long walletId, long amountHp) {
        ledgerRecordUseCase.record(new LedgerOperation("PAYOUT:lecture-objection-" + walletId,
                LedgerTransactionType.PAYOUT, BillingReferenceType.SETTLEMENT, walletId, null, Instant.now(),
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
    }

    private Lecture saveOnSaleLecture() {
        LocalDateTime now = LocalDateTime.now();
        Lecture lecture = Lecture.register(SELLER_ID, "방송 장비 세팅 강의", "처음 방송하는 사람을 위한 장비 세팅", 10_000L, null,
                List.of(new LectureContentSpec(101L, "1강 장비 고르기", ContentType.VIDEO)), now);
        lecture.approve(1L, now);
        return lectureRepository.save(lecture);
    }
}
