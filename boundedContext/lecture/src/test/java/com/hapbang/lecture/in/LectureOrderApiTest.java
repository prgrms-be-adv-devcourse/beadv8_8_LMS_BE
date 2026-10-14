package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// 주문 조회·직접 구매 확정 API를 HTTP부터 DB까지 관통해 검증한다. 상태 전이·기한 규칙은 LectureOrderTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureOrderApiTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long OTHER_BUYER_ID = 4L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Test
    void 내_주문_목록을_조회한다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));

        mockMvc.perform(get("/api/v1/lectures/orders").with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("주문 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].orderId").value(order.getId()))
                .andExpect(jsonPath("$.data.content[0].representativeTitle").value(TITLE))
                .andExpect(jsonPath("$.data.content[0].itemCount").value(1))
                .andExpect(jsonPath("$.data.content[0].progress").value("IN_PROGRESS"));
    }

    @Test
    void 주문_상세를_조회한다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.of(2026, 9, 30, 10, 0));

        mockMvc.perform(get("/api/v1/lectures/orders/{orderId}", order.getId())
                        .with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("주문을 조회했습니다."))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.items[0].orderItemId").value(order.getItems().getFirst().getId()))
                .andExpect(jsonPath("$.data.items[0].status").value("PURCHASED"))
                .andExpect(jsonPath("$.data.items[0].autoConfirmAt").value("2026-10-08T00:00:00"))
                .andExpect(jsonPath("$.data.items[0].payoutScheduledAt").value("2026-10-31T00:00:00"));
    }

    @Test
    void 남의_주문_상세는_404_ORDER_NOT_FOUND다() throws Exception {
        LectureOrder order = save(OTHER_BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));

        mockMvc.perform(get("/api/v1/lectures/orders/{orderId}", order.getId())
                        .with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void 구매를_확정하면_200과_항목을_응답한다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));
        Long orderItemId = order.getItems().getFirst().getId();

        mockMvc.perform(confirm(order.getId(), orderItemId).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("구매를 확정했습니다."))
                .andExpect(jsonPath("$.data.orderItemId").value(orderItemId))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmationType").value("DIRECT"));
    }

    @Test
    void 이미_확정한_항목을_다시_확정하면_409_ORDER_ITEM_NOT_PURCHASED다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));
        Long orderItemId = order.getItems().getFirst().getId();
        mockMvc.perform(confirm(order.getId(), orderItemId).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk());

        mockMvc.perform(confirm(order.getId(), orderItemId).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_NOT_PURCHASED"));
    }

    @Test
    void 관리자는_주문_API를_쓸_수_없다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));

        mockMvc.perform(get("/api/v1/lectures/orders").with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(confirm(order.getId(), order.getItems().getFirst().getId())
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder confirm(Long orderId, Long orderItemId) {
        return post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/confirm", orderId, orderItemId)
                .with(csrf());
    }

    private LectureOrder save(Long buyerUserId, LocalDateTime purchasedAt) {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        lectureRepository.save(lecture);
        return lectureOrderRepository.save(LectureOrderFixture.order(buyerUserId, List.of(lecture), purchasedAt));
    }
}
