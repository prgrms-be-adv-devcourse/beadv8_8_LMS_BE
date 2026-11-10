package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fake.FakeCouponApiClient;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

// 관리자의 주문 쿠폰 작업 목록·상세·다시 시도·결제 실패 확정·해제 확인 완료 API를 HTTP부터 DB까지 확인한다(쿠폰·지갑 Client는 Fake).
// 조치 분기는 OrderCouponWorkAdminUseCaseTest, 상태 전이·판정 규칙은 OrderCouponWorkTest·CouponReservationOutcomeTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LectureCouponWorkAdminApiTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final String BASE = "/api/v1/lectures/admin/coupon-works";
    private static final long DISCOUNT = 3_000L;
    private static final long MEMBER_COUPON_ID = 501L;
    private static final long RESERVED_GENERATION = 5L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private FakeCouponApiClient coupon;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        billing.reset();
        coupon.reset();
    }

    @Test
    void 관리자_목록은_주문_상태와_함께_최신순이고_상태와_종류로_거른다() throws Exception {
        Long pendingOrder = couponOrder();
        Long deadOrder = couponOrder();
        markDead(deadOrder);

        mockMvc.perform(get(BASE).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("쿠폰 작업 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].workId").value(reserveWorkId(deadOrder)))
                .andExpect(jsonPath("$.data.content[0].kind").value("RESERVE"))
                .andExpect(jsonPath("$.data.content[0].status").value("DEAD"))
                .andExpect(jsonPath("$.data.content[0].attemptCount").value(1))
                .andExpect(jsonPath("$.data.content[0].nextAttemptAt").isString())
                .andExpect(jsonPath("$.data.content[0].orderStatus").value("HOLD_PENDING"))
                .andExpect(jsonPath("$.data.content[1].workId").value(reserveWorkId(pendingOrder)));
        mockMvc.perform(get(BASE).param("status", "DEAD").param("kind", "RESERVE").with(admin()))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].orderId").value(deadOrder));
    }

    @Test
    void 상세는_쿠폰_계산_근거와_쿠폰_예약_상태를_주고_쿠폰을_부르지_못하면_UNAVAILABLE이다() throws Exception {
        Long orderId = couponOrder();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(reservation("RESERVED", orderItemId(orderId),
                "RESERVED")));
        coupon.respond(request -> new CouponCallResult.NotSent<>("회로 열림"));

        mockMvc.perform(get(BASE + "/{workId}", reserveWorkId(orderId)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.work.workId").value(reserveWorkId(orderId)))
                .andExpect(jsonPath("$.data.work.orderStatus").value("HOLD_PENDING"))
                .andExpect(jsonPath("$.data.coupons[0].memberCouponId").value(MEMBER_COUPON_ID))
                .andExpect(jsonPath("$.data.coupons[0].expectedGeneration").value(RESERVED_GENERATION - 1))
                .andExpect(jsonPath("$.data.coupons[0].reservedGeneration").doesNotExist())
                .andExpect(jsonPath("$.data.couponStatus").value("RESERVED"))
                .andExpect(jsonPath("$.data.couponReservation.items[0].coupons[0].status").value("RESERVED"));
        mockMvc.perform(get(BASE + "/{workId}", reserveWorkId(orderId)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.couponStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.couponReservation").doesNotExist());
        mockMvc.perform(get(BASE + "/{workId}", "lecture-order:999999:reserve").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUPON_WORK_NOT_FOUND"));
    }

    @Test
    void DEAD_다시_시도는_204이고_관리자_ID와_전후_상태를_로그로_남긴다(CapturedOutput output) throws Exception {
        Long orderId = couponOrder();
        markDead(orderId);

        mockMvc.perform(post(BASE + "/{workId}/retry", reserveWorkId(orderId)).with(admin()))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("select status from lectures.order_coupon_work where order_id = ?",
                String.class, orderId)).isEqualTo("PENDING");
        assertThat(output.getAll().lines().filter(line -> line.contains("관리자가 쿠폰 작업을 처리했다")))
                .singleElement().satisfies(line -> assertThat(line).contains(" INFO ", "action=다시 시도",
                        "adminUserId=" + ADMIN_ID, "workId=" + reserveWorkId(orderId), "before=DEAD", "after=PENDING"));
        mockMvc.perform(post(BASE + "/{workId}/retry", reserveWorkId(orderId)).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_WORK_NOT_DEAD"));
    }

    @Test
    void 결제_실패_확정은_쿠폰_예약_상태에_따라_세_결과를_주고_주문을_결제_실패로_끝낸다() throws Exception {
        Long reservedOrder = deadCouponOrder();
        Long rejectedOrder = deadCouponOrder();
        Long notFoundOrder = deadCouponOrder();
        // 쿠폰 예약이 남아 있음: 결제 실패 확정이 해제를 한 번 보낸다(Fake 해제는 정한 결과가 없어 결과 불명)
        coupon.respond(request -> new CouponCallResult.Succeeded<>(reservation("RESERVED",
                orderItemId(reservedOrder), "RESERVED")));
        mockMvc.perform(post(BASE + "/{workId}/fail", reserveWorkId(reservedOrder)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("쿠폰 주문의 결제 실패를 확정했습니다."))
                .andExpect(jsonPath("$.data.outcome").value("RESERVED_RELEASING"))
                .andExpect(jsonPath("$.data.work.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.work.orderStatus").value("HOLD_FAILED"));
        coupon.respond(request -> new CouponCallResult.Succeeded<>(
                new CouponReservationStatusResponse((String) request, "REJECTED", "COUPON_NOT_USABLE", List.of())));
        mockMvc.perform(post(BASE + "/{workId}/fail", reserveWorkId(rejectedOrder)).with(admin()))
                .andExpect(jsonPath("$.data.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.data.work.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.work.rejectCode").value("COUPON_NOT_USABLE"))
                .andExpect(jsonPath("$.data.work.orderStatus").value("HOLD_FAILED"));
        coupon.respond(request -> new CouponCallResult.Succeeded<>(
                new CouponReservationStatusResponse((String) request, "NOT_FOUND", null, List.of())));
        mockMvc.perform(post(BASE + "/{workId}/fail", reserveWorkId(notFoundOrder)).with(admin()))
                .andExpect(jsonPath("$.data.outcome").value("NOT_RESERVED"))
                .andExpect(jsonPath("$.data.work.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.work.rejectCode").value("ADMIN_FAILED_NOT_RESERVED"))
                .andExpect(jsonPath("$.data.work.orderStatus").value("HOLD_FAILED"));
        // 결제 실패가 확정되어 같은 강의를 다시 살 수 있는 상태가 됐고, 해제 작업은 쿠폰 쪽 예약이 남은 주문에만 있다
        assertThat(jdbcTemplate.queryForList("select order_id from lectures.order_coupon_work where kind = 'RELEASE'",
                Long.class)).containsExactly(reservedOrder);
    }

    @Test
    void 결제_실패_확정은_쿠폰_예약_상태를_조회할_수_없으면_503이고_아무것도_바꾸지_않는다() throws Exception {
        Long orderId = deadCouponOrder();
        coupon.respond(request -> new CouponCallResult.Unknown<>(0, "응답 시간 초과"));

        mockMvc.perform(post(BASE + "/{workId}/fail", reserveWorkId(orderId)).with(admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COUPON_UNAVAILABLE"));

        assertThat(jdbcTemplate.queryForObject("select status from lectures.lecture_order where id = ?", String.class,
                orderId)).isEqualTo("HOLD_PENDING");
    }

    @Test
    void 해제_확인_완료는_쿠폰_예약이_남지_않았으면_204로_해제_작업을_닫는다() throws Exception {
        Long orderId = deadCouponOrder();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(reservation("RESERVED", orderItemId(orderId),
                "RESERVED")));
        mockMvc.perform(post(BASE + "/{workId}/fail", reserveWorkId(orderId)).with(admin()))
                .andExpect(status().isOk());
        String releaseWorkId = "lecture-order:" + orderId + ":release";
        jdbcTemplate.update("update lectures.order_coupon_work set status = 'DEAD' where work_id = ?", releaseWorkId);
        coupon.respond(request -> new CouponCallResult.Succeeded<>(reservation("RELEASED", orderItemId(orderId),
                "RELEASED")));

        mockMvc.perform(post(BASE + "/{workId}/resolve", releaseWorkId).with(admin()))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("select status from lectures.order_coupon_work where work_id = ?",
                String.class, releaseWorkId)).isEqualTo("RELEASED");
    }

    @Test
    void 관리자가_아니면_쿠폰_작업_API를_쓸_수_없다() throws Exception {
        Long orderId = deadCouponOrder();
        String workId = reserveWorkId(orderId);

        for (MockHttpServletRequestBuilder request : List.of(get(BASE), get(BASE + "/{workId}", workId),
                post(BASE + "/{workId}/retry", workId), post(BASE + "/{workId}/fail", workId),
                post(BASE + "/{workId}/resolve", workId))) {
            mockMvc.perform(request.with(GatewayLogin.as(BUYER_ID, Role.USER))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("select status from lectures.order_coupon_work where order_id = ?",
                String.class, orderId)).isEqualTo("DEAD");
        assertThat(coupon.calls()).noneMatch(call -> workId.equals(call.request()));
    }

    /** 쿠폰 계산은 성공하고 예약 결과를 몰라(Fake 기본) 결제 처리 중인 쿠폰 주문 */
    private Long couponOrder() throws Exception {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request)));
        String response = mockMvc.perform(post("/api/v1/lectures/orders").with(GatewayLogin.as(BUYER_ID, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lectureIds": [%d], "expectedBuyerAmountHp": %d,
                                 "coupons": [{"memberCouponId": %d, "lectureId": %d}]}
                                """.formatted(lecture.getId(), PRICE_HP - DISCOUNT, MEMBER_COUPON_ID, lecture.getId())))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.orderId")).longValue();
    }

    private Long deadCouponOrder() throws Exception {
        Long orderId = couponOrder();
        markDead(orderId);
        return orderId;
    }

    private void markDead(Long orderId) {
        jdbcTemplate.update("update lectures.order_coupon_work set status = 'DEAD' where order_id = ?", orderId);
    }

    private Long orderItemId(Long orderId) {
        return jdbcTemplate.queryForObject("select id from lectures.order_item where order_id = ?", Long.class, orderId);
    }

    private static String reserveWorkId(Long orderId) {
        return "lecture-order:" + orderId + ":reserve";
    }

    private static RequestPostProcessor admin() {
        return GatewayLogin.as(ADMIN_ID, Role.ADMIN);
    }

    private static CouponReservationStatusResponse reservation(String status, Long orderItemId, String usageStatus) {
        return new CouponReservationStatusResponse("ignored", status, null, List.of(
                new CouponReservationStatusResponse.Item(orderItemId, DISCOUNT, 0L, DISCOUNT, List.of(
                        new CouponReservationStatusResponse.Coupon(MEMBER_COUPON_ID, 11L, RESERVED_GENERATION, 1,
                                PRICE_HP, DISCOUNT, 0L, DISCOUNT, 2, usageStatus)))));
    }

    private static CouponCalculateResponse calculated(CouponCalculateRequest request) {
        CouponCalculateRequest.Item item = request.items().getFirst();
        return new CouponCalculateResponse(false, false, List.of(new CouponCalculateResponse.Item(item.lectureId(),
                item.price(), DISCOUNT, 0, DISCOUNT, item.price() - DISCOUNT, item.price() - DISCOUNT,
                List.of(new CouponCalculateResponse.Coupon(MEMBER_COUPON_ID, 11L, "SELLER", 1, item.price(), DISCOUNT,
                        0, DISCOUNT, RESERVED_GENERATION - 1, 2)))), DISCOUNT, item.price() - DISCOUNT);
    }

    private Lecture saveOnSale() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }
}
