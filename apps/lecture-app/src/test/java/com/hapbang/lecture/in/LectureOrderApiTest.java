package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

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
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fake.FakeCouponClient;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.out.BillingCallExceptions;
import com.hapbang.shared.billing.out.BillingCallFailureKind;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
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
    private FakeBillingApiClient billing;

    @Autowired
    private FakeCouponClient coupon;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP);
        coupon.reset();
    }

    @Test
    void 주문하면_201과_주문을_응답한다() throws Exception {
        Lecture lecture = saveOnSaleLecture();

        mockMvc.perform(createOrder(lecture.getId()).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("주문했습니다."))
                .andExpect(jsonPath("$.data.orderId").isNumber())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.totalAmountHp").value(PRICE_HP))
                // 쿠폰 없는 주문은 할인 0, 구매자 금액 = 가격
                .andExpect(jsonPath("$.data.discountHp").value(0))
                .andExpect(jsonPath("$.data.buyerAmountHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].lectureId").value(lecture.getId()))
                .andExpect(jsonPath("$.data.items[0].status").value("PURCHASED"))
                .andExpect(jsonPath("$.data.paidAt").isString())
                .andExpect(jsonPath("$.data.processingStatus").doesNotExist());
    }

    @Test
    void 한_주문에_강의를_100개보다_많이_담으면_400_INVALID_REQUEST이고_주문하지_않는다() throws Exception {
        String lectureIds = LongStream.rangeClosed(1, 101)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/api/v1/lectures/orders")
                        .with(GatewayLogin.as(BUYER_ID, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\": [" + lectureIds + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("lectureIds"));

        assertThat(lectureOrderRepository.count()).isZero();
        assertThat(billing.calls()).isEmpty();
    }

    @Test
    void 쿠폰을_고르고_결제_금액을_보내지_않거나_주문하지_않는_강의에_쓰면_400_INVALID_REQUEST이고_쿠폰을_부르지_않는다()
            throws Exception {
        Lecture lecture = saveOnSaleLecture();

        mockMvc.perform(createOrderWith("{\"lectureIds\": [" + lecture.getId() + "], "
                        + "\"coupons\": [{\"memberCouponId\": 501, \"lectureId\": " + lecture.getId() + "}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("expectedAmountPresentWithCoupons"));
        mockMvc.perform(createOrderWith("{\"lectureIds\": [" + lecture.getId() + "], \"expectedBuyerAmountHp\": 0, "
                        + "\"coupons\": [{\"memberCouponId\": 501, \"lectureId\": 99999}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("couponLectureOrdered"));
        mockMvc.perform(createOrderWith("{\"lectureIds\": [" + lecture.getId() + "], \"expectedBuyerAmountHp\": -1, "
                        + "\"coupons\": [{\"memberCouponId\": null, \"lectureId\": " + lecture.getId() + "}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));

        assertThat(lectureOrderRepository.count()).isZero();
        assertThat(coupon.calls()).isEmpty();
    }

    @Test
    void 쿠폰_계산이_거절되면_409에_쿠폰_오류_코드와_발급_쿠폰_ID를_싣는다() throws Exception {
        Lecture lecture = saveOnSaleLecture();
        coupon.respond(request -> new CouponCallResult.Rejected<>(409, "COUPON_NOT_USABLE", 501L, null));

        mockMvc.perform(createOrderWith("{\"lectureIds\": [" + lecture.getId() + "], \"expectedBuyerAmountHp\": "
                        + PRICE_HP + ", \"coupons\": [{\"memberCouponId\": 501, \"lectureId\": " + lecture.getId()
                        + "}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_CALCULATION_REJECTED"))
                .andExpect(jsonPath("$.couponCode").value("COUPON_NOT_USABLE"))
                .andExpect(jsonPath("$.memberCouponId").value(501))
                .andExpect(jsonPath("$.couponDetail").doesNotExist());

        // 플랫폼 재원 비활성 거절은 FE가 만료 등 다른 거절과 구분하도록 사유를 couponDetail로 싣는다
        coupon.respond(request -> new CouponCallResult.Rejected<>(409, "COUPON_NOT_USABLE", 502L,
                "PLATFORM_FUNDING_DISABLED"));
        mockMvc.perform(createOrderWith("{\"lectureIds\": [" + lecture.getId() + "], \"expectedBuyerAmountHp\": "
                        + PRICE_HP + ", \"coupons\": [{\"memberCouponId\": 502, \"lectureId\": " + lecture.getId()
                        + "}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_CALCULATION_REJECTED"))
                .andExpect(jsonPath("$.couponDetail").value("PLATFORM_FUNDING_DISABLED"));

        assertThat(lectureOrderRepository.count()).isZero();
    }

    @Test
    void 보류_결과를_모르면_202와_결제_처리_중_주문을_응답한다() throws Exception {
        Lecture lecture = saveOnSaleLecture();
        billing.respond(command -> {
            throw BillingCallExceptions.of(BillingCallFailureKind.UNKNOWN_RESULT, 0, null);
        });

        mockMvc.perform(createOrder(lecture.getId()).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusCode").value(202))
                .andExpect(jsonPath("$.message").value("주문 결제를 처리 중입니다."))
                .andExpect(jsonPath("$.data.orderId").isNumber())
                .andExpect(jsonPath("$.data.status").value("HOLD_PENDING"))
                .andExpect(jsonPath("$.data.processingStatus").value("DELAYED"))
                .andExpect(jsonPath("$.data.items[0].status").value("HOLD_PENDING"))
                .andExpect(jsonPath("$.data.items[0].processingStatus").value("DELAYED"));
    }

    @Test
    void 잔액이_부족하면_409_INSUFFICIENT_BALANCE다() throws Exception {
        Lecture lecture = saveOnSaleLecture();
        billing.respond(command -> new BillingCallResult.Failed<>(BillingFailureCode.INSUFFICIENT_BALANCE,
                "HP 잔액이 부족합니다.", 409, "INSUFFICIENT_BALANCE"));

        mockMvc.perform(createOrder(lecture.getId()).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void 탈퇴로_닫힌_지갑이면_결제_실패로_409_WALLET_CLOSED다() throws Exception {
        // 결제 실패 사유를 보류 작업의 확정 거절 기록에서 읽어 잔액 부족과 나눈다 (D76)
        Lecture lecture = saveOnSaleLecture();
        billing.respond(command -> new BillingCallResult.Failed<>(BillingFailureCode.WALLET_CLOSED,
                "탈퇴한 회원의 지갑이에요.", 409, "WALLET_CLOSED"));

        mockMvc.perform(createOrder(lecture.getId()).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_CLOSED"))
                .andExpect(jsonPath("$.detail").value("탈퇴한 회원의 지갑이에요."));
    }

    @Test
    void 구매_취소하면_200과_CANCELED_항목을_응답한다() throws Exception {
        String body = mockMvc.perform(createOrder(saveOnSaleLecture().getId()).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(body).read("$.data.orderId", Long.class);
        Long orderItemId = JsonPath.parse(body).read("$.data.items[0].orderItemId", Long.class);

        mockMvc.perform(cancelItem(orderId, orderItemId).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("구매를 취소했습니다."))
                .andExpect(jsonPath("$.data.orderItemId").value(orderItemId))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.canceledAt").isString());
    }

    @Test
    void 반환이_끝나지_않으면_202와_구매_취소_처리_중_항목을_응답한다() throws Exception {
        String body = mockMvc.perform(createOrder(saveOnSaleLecture().getId()).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(body).read("$.data.orderId", Long.class);
        Long orderItemId = JsonPath.parse(body).read("$.data.items[0].orderItemId", Long.class);
        billing.respond(command -> {
            throw BillingCallExceptions.of(BillingCallFailureKind.UNKNOWN_RESULT, 0, null);
        });

        mockMvc.perform(cancelItem(orderId, orderItemId).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusCode").value(202))
                .andExpect(jsonPath("$.message").value("구매 취소를 처리 중입니다."))
                .andExpect(jsonPath("$.data.status").value("CANCEL_PENDING"))
                .andExpect(jsonPath("$.data.processingStatus").value("DELAYED"))
                .andExpect(jsonPath("$.data.canceledAt").doesNotExist());
        // 처리 중 항목은 주문 상세에도 처리 상태가 보이고, 시청 가능한 강의에서는 빠진다 (D59 (2))
        mockMvc.perform(get("/api/v1/lectures/orders/{orderId}", orderId).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(jsonPath("$.data.processingStatus").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].status").value("CANCEL_PENDING"))
                .andExpect(jsonPath("$.data.items[0].processingStatus").value("DELAYED"));
        mockMvc.perform(get("/api/v1/lectures/purchased").with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    void 기간이_지난_구매_취소는_409_CANCEL_PERIOD_EXPIRED다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE).minusDays(8));

        mockMvc.perform(cancelItem(order.getId(), order.getItems().getFirst().getId())
                        .with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_PERIOD_EXPIRED"));
    }

    @Test
    void 내_주문_목록을_조회한다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));

        mockMvc.perform(get("/api/v1/lectures/orders").with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("주문 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].orderId").value(order.getId()))
                .andExpect(jsonPath("$.data.content[0].representativeTitle").value(TITLE))
                .andExpect(jsonPath("$.data.content[0].itemCount").value(1))
                .andExpect(jsonPath("$.data.content[0].progress").value("IN_PROGRESS"))
                // 쿠폰을 쓰지 않은 주문은 할인 0, 결제액 = 가격 합
                .andExpect(jsonPath("$.data.content[0].totalAmountHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.content[0].discountHp").value(0))
                .andExpect(jsonPath("$.data.content[0].buyerAmountHp").value(PRICE_HP));
    }

    @Test
    void 결제_처리_중인_주문도_목록과_상세에_보이고_처리_상태가_있다() throws Exception {
        Lecture lecture = saveOnSaleLecture();
        billing.respond(command -> {
            throw BillingCallExceptions.of(BillingCallFailureKind.UNKNOWN_RESULT, 0, null);
        });
        String body = mockMvc.perform(createOrder(lecture.getId()).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(body).read("$.data.orderId", Long.class);

        mockMvc.perform(get("/api/v1/lectures/orders").with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].orderId").value(orderId))
                .andExpect(jsonPath("$.data.content[0].status").value("HOLD_PENDING"))
                .andExpect(jsonPath("$.data.content[0].processingStatus").value("DELAYED"))
                .andExpect(jsonPath("$.data.content[0].progress").doesNotExist());
        mockMvc.perform(get("/api/v1/lectures/orders/{orderId}", orderId).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HOLD_PENDING"))
                .andExpect(jsonPath("$.data.paidAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].purchasedAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].processingStatus").value("DELAYED"));
        // 결제가 끝나지 않은 강의는 시청 가능한 강의에 없다
        mockMvc.perform(get("/api/v1/lectures/purchased").with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    void 주문_상세를_조회한다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.of(2026, 9, 30, 10, 0));

        mockMvc.perform(get("/api/v1/lectures/orders/{orderId}", order.getId())
                        .with(GatewayLogin.as(BUYER_ID, Role.USER)))
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
                        .with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void 구매를_확정하면_200과_항목을_응답한다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));
        Long orderItemId = order.getItems().getFirst().getId();

        mockMvc.perform(confirm(order.getId(), orderItemId).with(GatewayLogin.as(BUYER_ID, Role.USER)))
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
        mockMvc.perform(confirm(order.getId(), orderItemId).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk());

        mockMvc.perform(confirm(order.getId(), orderItemId).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_NOT_PURCHASED"));
    }

    @Test
    void 관리자는_주문_API를_쓸_수_없다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));
        Long orderItemId = order.getItems().getFirst().getId();

        mockMvc.perform(get("/api/v1/lectures/orders").with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(confirm(order.getId(), orderItemId).with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(playConsent(order.getId(), orderItemId).with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/lectures/purchased").with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(createOrder(saveOnSaleLecture().getId()).with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(cancelItem(order.getId(), orderItemId).with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/lectures/sales").with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 쿠폰_할인_주문은_주문_상세와_목록에_할인과_구매자_금액을_싣고_판매_내역에_판매자_플랫폼_부담과_지급액을_싣는다() throws Exception {
        Lecture lecture = saveOnSaleLecture();
        LocalDateTime now = LocalDateTime.now(LectureConfig.ZONE);
        // 할인 4,000 = 플랫폼 1,000 + 판매자 3,000. 판매자에게도 플랫폼 부담이 보인다
        LectureOrder placed = LectureOrder.place(BUYER_ID, List.of(lecture), Set.of(), now);
        placed.applyItemDiscount(lecture.getId(), 4_000L, 1_000L, 3_000L);
        placed.markPaid(now);
        LectureOrder order = lectureOrderRepository.save(placed);

        mockMvc.perform(get("/api/v1/lectures/orders/{orderId}", order.getId())
                        .with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAmountHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.discountHp").value(4_000))
                .andExpect(jsonPath("$.data.buyerAmountHp").value(PRICE_HP - 4_000))
                .andExpect(jsonPath("$.data.items[0].priceHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.items[0].discountHp").value(4_000))
                .andExpect(jsonPath("$.data.items[0].buyerAmountHp").value(PRICE_HP - 4_000));
        // 주문 목록 카드도 상세를 받기 전에 할인 뒤 결제액을 보인다
        mockMvc.perform(get("/api/v1/lectures/orders").with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].totalAmountHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.content[0].discountHp").value(4_000))
                .andExpect(jsonPath("$.data.content[0].buyerAmountHp").value(PRICE_HP - 4_000));
        mockMvc.perform(get("/api/v1/lectures/sales").with(GatewayLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].priceHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.content[0].discountHp").value(4_000))
                .andExpect(jsonPath("$.data.content[0].sellerDiscountHp").value(3_000))
                .andExpect(jsonPath("$.data.content[0].platformDiscountHp").value(1_000))
                .andExpect(jsonPath("$.data.content[0].sellerPayoutHp").value(PRICE_HP - 3_000));
    }

    @Test
    void 판매자는_판매_내역에서_항목_상태와_지급_예정_시각을_최근_구매순으로_본다() throws Exception {
        LocalDateTime now = LocalDateTime.now(LectureConfig.ZONE);
        LectureOrder paidOut = save(BUYER_ID, now.minusDays(32));
        Long paidOutItemId = paidOut.getItems().getFirst().getId();
        inTransaction(paidOut, order -> {
            order.confirmItem(paidOutItemId, now.minusDays(32));
            LectureOrderFixture.payOut(order, paidOutItemId, now);
        });
        LectureOrder canceled = save(OTHER_BUYER_ID, now.minusDays(1));
        cancel(canceled, now.minusDays(1));
        Lecture otherSellers = Lecture.register(OTHER_BUYER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(),
                NOW);
        otherSellers.approve(ADMIN_ID, NOW);
        saveOrder(BUYER_ID, lectureRepository.save(otherSellers), now);

        mockMvc.perform(get("/api/v1/lectures/sales").with(GatewayLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
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
                // 쿠폰 없는 주문은 할인 0, 지급액 = 가격
                .andExpect(jsonPath("$.data.content[1].discountHp").value(0))
                .andExpect(jsonPath("$.data.content[1].sellerPayoutHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.content[1].status").value("PAID_OUT"))
                .andExpect(jsonPath("$.data.content[1].purchasedAt").isString())
                .andExpect(jsonPath("$.data.content[1].payoutScheduledAt").isString())
                .andExpect(jsonPath("$.data.content[1].paidOutAt").isString())
                .andExpect(jsonPath("$.data.content[1].objectionStatus").value("NONE"));
    }

    @Test
    void 인증된_인플루언서가_아니면_판매_내역을_볼_수_없다() throws Exception {
        mockMvc.perform(get("/api/v1/lectures/sales").with(GatewayLogin.as(SELLER_ID, Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/lectures/sales").with(GatewayLogin.as(SELLER_ID, Role.INFLUENCER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/lectures/sales"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 재생_동의하면_200과_FIRST_PLAY_항목을_응답한다() throws Exception {
        LectureOrder order = save(BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));
        Long orderItemId = order.getItems().getFirst().getId();

        mockMvc.perform(playConsent(order.getId(), orderItemId).with(GatewayLogin.as(BUYER_ID, Role.USER)))
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
        mockMvc.perform(confirm(order.getId(), orderItemId).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk());

        mockMvc.perform(playConsent(order.getId(), orderItemId).with(GatewayLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmationType").value("DIRECT"));
    }

    @Test
    void 남의_주문_항목에_재생_동의하면_404_ORDER_NOT_FOUND다() throws Exception {
        LectureOrder order = save(OTHER_BUYER_ID, LocalDateTime.now(LectureConfig.ZONE));

        mockMvc.perform(playConsent(order.getId(), order.getItems().getFirst().getId())
                        .with(GatewayLogin.as(BUYER_ID, Role.USER)))
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

        mockMvc.perform(get("/api/v1/lectures/purchased").with(GatewayLogin.as(BUYER_ID, Role.USER)))
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

    private static MockHttpServletRequestBuilder createOrderWith(String body) {
        return post("/api/v1/lectures/orders")
                .with(GatewayLogin.as(BUYER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder createOrder(Long lectureId) {
        return post("/api/v1/lectures/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lectureIds\": [" + lectureId + "]}");
    }

    private static MockHttpServletRequestBuilder cancelItem(Long orderId, Long orderItemId) {
        return post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId, orderItemId);
    }

    private static MockHttpServletRequestBuilder confirm(Long orderId, Long orderItemId) {
        return post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/confirm", orderId, orderItemId);
    }

    private static MockHttpServletRequestBuilder playConsent(Long orderId, Long orderItemId) {
        return post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/play-consent", orderId, orderItemId);
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

    /** HP 작업 없이 구매 취소까지 끝낸 상태를 만든다. 항목 변경은 영속 상태에서만 저장된다 */
    private void cancel(LectureOrder order, LocalDateTime now) {
        transactionTemplate.executeWithoutResult(status -> LectureOrderFixture.cancel(
                lectureOrderRepository.findById(order.getId()).orElseThrow(), order.getItems().getFirst().getId(),
                now));
    }
}
