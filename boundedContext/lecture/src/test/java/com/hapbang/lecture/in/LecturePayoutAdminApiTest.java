package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// 관리자의 지급 실패 목록·지급 격리 해제 API를 HTTP부터 DB까지 확인한다 (D54). 실패 분류·기록은 OrderPayoutUseCaseTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LecturePayoutAdminApiTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final LocalDateTime PURCHASED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 10, 3, 1, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 관리자는_지급_실패_목록을_최근_실패순으로_조회한다() throws Exception {
        LectureOrder transientFailure = saveConfirmed();
        recordFailure(transientFailure, "WALLET_NOT_FOUND", false, FAILED_AT);
        LectureOrder quarantined = saveConfirmed();
        recordFailure(quarantined, "PAYOUT_ALREADY_SETTLED", true, FAILED_AT.plusDays(1));
        saveConfirmed();

        mockMvc.perform(get("/api/v1/lectures/admin/payout-failures").param("sort", "id,asc")
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("지급 실패 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].orderItemId").value(itemId(quarantined)))
                .andExpect(jsonPath("$.data.content[0].orderId").value(quarantined.getId()))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(item(quarantined).getLectureId()))
                .andExpect(jsonPath("$.data.content[0].sellerUserId").value(SELLER_ID))
                .andExpect(jsonPath("$.data.content[0].buyerUserId").value(BUYER_ID))
                .andExpect(jsonPath("$.data.content[0].priceHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.content[0].payoutScheduledAt").value("2026-10-02T00:00:00"))
                .andExpect(jsonPath("$.data.content[0].payoutFailureCount").value(1))
                .andExpect(jsonPath("$.data.content[0].lastPayoutFailureCode").value("PAYOUT_ALREADY_SETTLED"))
                .andExpect(jsonPath("$.data.content[0].lastPayoutFailedAt").value("2026-10-04T01:00:00"))
                .andExpect(jsonPath("$.data.content[0].payoutQuarantinedAt").value("2026-10-04T01:00:00"))
                .andExpect(jsonPath("$.data.content[1].orderItemId").value(itemId(transientFailure)))
                .andExpect(jsonPath("$.data.content[1].payoutQuarantinedAt").isEmpty());
    }

    @Test
    void 관리자가_아니면_지급_실패_목록을_볼_수_없다() throws Exception {
        mockMvc.perform(get("/api/v1/lectures/admin/payout-failures")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 격리를_해제하면_204이고_실패_기록은_남는다() throws Exception {
        LectureOrder order = saveConfirmed();
        recordFailure(order, "PAYOUT_ALREADY_SETTLED", true, FAILED_AT);

        mockMvc.perform(post("/api/v1/lectures/admin/payout-failures/{orderItemId}/retry", itemId(order))
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(item(order).getPayoutQuarantinedAt()).isNull();
        assertThat(item(order).getPayoutFailureCount()).isEqualTo(1);
    }

    @Test
    void 없는_항목의_격리를_해제하면_404_ORDER_ITEM_NOT_FOUND다() throws Exception {
        mockMvc.perform(post("/api/v1/lectures/admin/payout-failures/{orderItemId}/retry", 999_999L)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_NOT_FOUND"));
    }

    @Test
    void 격리되지_않은_항목의_격리를_해제하면_409_PAYOUT_NOT_QUARANTINED다() throws Exception {
        LectureOrder order = saveConfirmed();
        recordFailure(order, "WALLET_NOT_FOUND", false, FAILED_AT);

        mockMvc.perform(post("/api/v1/lectures/admin/payout-failures/{orderItemId}/retry", itemId(order))
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYOUT_NOT_QUARANTINED"));
    }

    @Test
    void 관리자가_아니면_격리를_해제할_수_없다() throws Exception {
        LectureOrder order = saveConfirmed();
        recordFailure(order, "PAYOUT_ALREADY_SETTLED", true, FAILED_AT);

        mockMvc.perform(post("/api/v1/lectures/admin/payout-failures/{orderItemId}/retry", itemId(order))
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(item(order).isPayoutQuarantined()).isTrue();
    }

    /** 9-1에 사서 바로 구매 확정한 주문 (지급 예정 시각 10-02 00:00) */
    private LectureOrder saveConfirmed() {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lecture),
                PURCHASED_AT));
        transactionTemplate.executeWithoutResult(status -> lectureOrderRepository.findById(order.getId())
                .orElseThrow().confirmItem(itemId(order), PURCHASED_AT));
        return order;
    }

    private void recordFailure(LectureOrder order, String code, boolean quarantine, LocalDateTime failedAt) {
        transactionTemplate.executeWithoutResult(status -> lectureOrderRepository.findById(order.getId())
                .orElseThrow().recordPayoutFailure(itemId(order), code, quarantine, failedAt));
    }

    private OrderItem item(LectureOrder order) {
        return transactionTemplate.execute(status ->
                lectureOrderRepository.findById(order.getId()).orElseThrow().getItems().getFirst());
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }
}
