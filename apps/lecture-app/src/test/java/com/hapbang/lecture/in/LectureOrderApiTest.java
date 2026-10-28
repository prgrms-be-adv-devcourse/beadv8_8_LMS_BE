package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.fake.FakeBillingPort;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

// 주문·주문 조회·직접 구매 확정·구매 취소·재생 동의·시청 가능한 강의·판매 내역 API를 HTTP부터 DB까지 관통해 검증한다. 상태 전이·기한 규칙은 LectureOrderTest가 맡는다.
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

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FakeBillingPort billingPort;

    @BeforeEach
    void setUp() {
        billingPort.reset();
    }

    @Test
    void 주문하면_201과_주문을_응답한다() throws Exception {
        Lecture lecture = saveOnSaleLecture();

        mockMvc.perform(createOrder(lecture.getId()).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("주문했습니다."))
                .andExpect(jsonPath("$.data.orderId").isNumber())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.totalAmountHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].lectureId").value(lecture.getId()))
                .andExpect(jsonPath("$.data.items[0].status").value("PURCHASED"));
    }

    @Test
    void 잔액이_부족하면_409_INSUFFICIENT_BALANCE다() throws Exception {
        Lecture lecture = saveOnSaleLecture();
        billingPort.setBalance(BUYER_ID, PRICE_HP - 1_000L);

        mockMvc.perform(createOrder(lecture.getId()).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void 구매_취소하면_200과_CANCELED_항목을_응답한다() throws Exception {
        String body = mockMvc.perform(createOrder(saveOnSaleLecture().getId()).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(body).read("$.data.orderId", Long.class);
        Long orderItemId = JsonPath.parse(body).read("$.data.items[0].orderItemId", Long.class);

        mockMvc.perform(cancelItem(orderId, orderItemId).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("구매를 취소했습니다."))
                .andExpect(jsonPath("$.data.orderItemId").value(orderItemId))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.canceledAt").isString());
    }

    @Test
    void 기간이_지난_구매_취소는_409_CANCEL_PERIOD_EXPIRED다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE).minusDays(8));

        mockMvc.perform(cancelItem(order.getId(), order.getItems().getFirst().getId())
                        .with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_PERIOD_EXPIRED"));
    }

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
        Long orderItemId = order.getItems().getFirst().getId();

        mockMvc.perform(get("/api/v1/lectures/orders").with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(confirm(order.getId(), orderItemId).with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(playConsent(order.getId(), orderItemId).with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/lectures/purchased").with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(createOrder(saveOnSaleLecture().getId()).with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(cancelItem(order.getId(), orderItemId).with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/lectures/sales").with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 판매자는_판매_내역에서_항목_상태와_지급_예정_시각을_최근_구매순으로_본다() throws Exception {
        LocalDateTime now = LocalDateTime.now(LectureConfig.ZONE);
        LectureOrder paidOut = save(BUYER_ID, now.minusDays(32));
        Long paidOutItemId = paidOut.getItems().getFirst().getId();
        inTransaction(paidOut, order -> {
            order.confirmItem(paidOutItemId, now.minusDays(32));
            order.payOutItem(paidOutItemId, now);
        });
        LectureOrder canceled = save(OTHER_BUYER_ID, now.minusDays(1));
        cancel(canceled, now.minusDays(1));
        Lecture otherSellers = Lecture.register(OTHER_BUYER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(),
                NOW);
        otherSellers.approve(ADMIN_ID, NOW);
        saveOrder(BUYER_ID, lectureRepository.save(otherSellers), now);

        mockMvc.perform(get("/api/v1/lectures/sales").with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("판매 내역을 조회했습니다."))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].orderItemId").value(canceled.getItems().getFirst().getId()))
                .andExpect(jsonPath("$.data.content[0].status").value("CANCELED"))
                .andExpect(jsonPath("$.data.content[0].paidOutAt").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].orderItemId").value(paidOutItemId))
                .andExpect(jsonPath("$.data.content[1].lectureId").value(paidOut.getItems().getFirst().getLectureId()))
                .andExpect(jsonPath("$.data.content[1].lectureTitle").value(TITLE))
                .andExpect(jsonPath("$.data.content[1].priceHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.content[1].status").value("PAID_OUT"))
                .andExpect(jsonPath("$.data.content[1].purchasedAt").isString())
                .andExpect(jsonPath("$.data.content[1].payoutScheduledAt").isString())
                .andExpect(jsonPath("$.data.content[1].paidOutAt").isString())
                .andExpect(jsonPath("$.data.content[1].objectionStatus").value("NONE"));
    }

    @Test
    void 인증된_인플루언서가_아니면_판매_내역을_볼_수_없다() throws Exception {
        mockMvc.perform(get("/api/v1/lectures/sales").with(TestLogin.as(SELLER_ID, Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/lectures/sales").with(TestLogin.as(SELLER_ID, Role.INFLUENCER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/lectures/sales"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 재생_동의하면_200과_FIRST_PLAY_항목을_응답한다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));
        Long orderItemId = order.getItems().getFirst().getId();

        mockMvc.perform(playConsent(order.getId(), orderItemId).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.orderItemId").value(orderItemId))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmationType").value("FIRST_PLAY"))
                .andExpect(jsonPath("$.data.confirmedAt").isString());
    }

    @Test
    void 이미_확정한_항목에_재생_동의하면_200과_그대로의_항목을_응답한다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));
        Long orderItemId = order.getItems().getFirst().getId();
        mockMvc.perform(confirm(order.getId(), orderItemId).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk());

        mockMvc.perform(playConsent(order.getId(), orderItemId).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmationType").value("DIRECT"));
    }

    @Test
    void 남의_주문_항목에_재생_동의하면_404_ORDER_NOT_FOUND다() throws Exception {
        LectureOrder order = save(OTHER_BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));

        mockMvc.perform(playConsent(order.getId(), order.getItems().getFirst().getId())
                        .with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void 시청_가능한_강의를_최근_구매순으로_조회하고_삭제된_강의도_포함한다() throws Exception {
        LocalDateTime now = LocalDateTime.now(LectureConfig.ZONE);
        Lecture deleted = saveOnSaleLecture();
        LectureOrder deletedOrder = saveOrder(BUYER_ID, deleted, now.minusDays(2));
        deleted.delete(SELLER_ID, now);
        lectureRepository.save(deleted);
        Lecture renamed = saveOnSaleLecture();
        LectureOrder renamedOrder = saveOrder(BUYER_ID, renamed, now.minusDays(1));
        renamed.updateInfo(SELLER_ID, "바뀐 제목", DESCRIPTION, PRICE_HP, null, now);
        lectureRepository.save(renamed);
        cancel(saveOrder(BUYER_ID, saveOnSaleLecture(), now), now);
        saveOrder(OTHER_BUYER_ID, saveOnSaleLecture(), now);

        mockMvc.perform(get("/api/v1/lectures/purchased").with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(renamed.getId()))
                .andExpect(jsonPath("$.data.content[0].orderId").value(renamedOrder.getId()))
                .andExpect(jsonPath("$.data.content[0].orderItemId")
                        .value(renamedOrder.getItems().getFirst().getId()))
                .andExpect(jsonPath("$.data.content[0].title").value("바뀐 제목"))
                .andExpect(jsonPath("$.data.content[0].itemStatus").value("PURCHASED"))
                .andExpect(jsonPath("$.data.content[0].lectureDeleted").value(false))
                .andExpect(jsonPath("$.data.content[1].lectureId").value(deleted.getId()))
                .andExpect(jsonPath("$.data.content[1].orderId").value(deletedOrder.getId()))
                .andExpect(jsonPath("$.data.content[1].lectureDeleted").value(true));
    }

    private static MockHttpServletRequestBuilder createOrder(Long lectureId) {
        return post("/api/v1/lectures/orders").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lectureIds\": [" + lectureId + "]}");
    }

    private static MockHttpServletRequestBuilder cancelItem(Long orderId, Long orderItemId) {
        return post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId, orderItemId)
                .with(csrf());
    }

    private static MockHttpServletRequestBuilder confirm(Long orderId, Long orderItemId) {
        return post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/confirm", orderId, orderItemId)
                .with(csrf());
    }

    private static MockHttpServletRequestBuilder playConsent(Long orderId, Long orderItemId) {
        return post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/play-consent", orderId, orderItemId)
                .with(csrf());
    }

    private LectureOrder save(Long buyerUserId, LocalDateTime purchasedAt) {
        return saveOrder(buyerUserId, saveOnSaleLecture(), purchasedAt);
    }

    private Lecture saveOnSaleLecture() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private LectureOrder saveOrder(Long buyerUserId, Lecture lecture, LocalDateTime purchasedAt) {
        return lectureOrderRepository.save(LectureOrderFixture.order(buyerUserId, List.of(lecture), purchasedAt));
    }

    private void inTransaction(LectureOrder order, Consumer<LectureOrder> change) {
        transactionTemplate.executeWithoutResult(status ->
                change.accept(lectureOrderRepository.findById(order.getId()).orElseThrow()));
    }

    /** HP 보류 없이 저장한 주문이라 구매 취소 API 대신 도메인 메서드로 취소한다. 항목 변경은 영속 상태에서만 저장된다 */
    private void cancel(LectureOrder order, LocalDateTime now) {
        transactionTemplate.executeWithoutResult(status -> lectureOrderRepository.findById(order.getId())
                .orElseThrow()
                .cancelItem(order.getItems().getFirst().getId(), now));
    }
}
