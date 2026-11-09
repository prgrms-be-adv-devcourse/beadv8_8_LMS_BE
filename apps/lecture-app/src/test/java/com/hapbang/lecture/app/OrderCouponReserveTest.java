package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.lecture.app.dto.OrderCouponSelection;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fake.FakeCouponClient;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 쿠폰 주문 2~3단계(트랜잭션 밖 쿠폰 예약 → 결과 기록과 보류 작업 등록)를 Fake 쿠폰·지갑 Client와 DB로 확인한다.
 * 응답과 스냅샷의 대조 규칙은 OrderCouponReservationTest가 맡는다.
 */
@IntegrationTest
@WithMockUser(roles = "USER")
class OrderCouponReserveTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final long SELLER_DISCOUNT = 3_000L;
    private static final long COUPON_ID = 11L;
    private static final long EXPECTED_GENERATION = 4L;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private FakeCouponClient coupon;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP);
        coupon.reset();
    }

    @Test
    void 예약에_성공하면_예약_generation을_남기고_그_뒤에야_보류_작업을_등록해_결제한다() {
        Lecture lecture = saveOnSale();
        AtomicLong hpTasksDuringReserve = new AtomicLong(-1);
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request,
                Map.of(lecture.getId(), SELLER_DISCOUNT))));
        coupon.respond(request -> {
            hpTasksDuringReserve.set(count("lectures.lecture_hp_task"));
            return new CouponCallResult.Succeeded<>(reserved((CouponReserveRequest) request));
        });

        OrderResponse response = order(List.of(lecture), PRICE_HP - SELLER_DISCOUNT);

        // 쿠폰 예약 결과가 저장되기 전에는 HP 보류 작업이 없다
        assertThat(hpTasksDuringReserve).hasValue(0);
        assertThat(response.status()).isEqualTo(LectureOrderStatus.PAID);
        Long orderItemId = response.items().getFirst().orderItemId();
        assertThat(coupon.calls().get(1).request()).isEqualTo(new CouponReserveRequest(BUYER_ID, response.orderId(),
                "lecture-order:" + response.orderId() + ":reserve", List.of(new CouponReserveRequest.Item(orderItemId,
                lecture.getId(), SELLER_ID, PRICE_HP, List.of(new CouponReserveRequest.Coupon(501L,
                EXPECTED_GENERATION, 2, 1, SELLER_DISCOUNT, 0L, SELLER_DISCOUNT))))));
        assertThat(coupon.calls().get(1).transactionActive()).isFalse();
        assertThat(jdbcTemplate.queryForObject("select reserved_generation from lectures.order_item_coupon "
                + "where order_item_id = ?", Long.class, orderItemId)).isEqualTo(EXPECTED_GENERATION + 1);
        assertThat(workStatus(response.orderId())).isEqualTo("RESERVED");
        assertThat(billing.calls()).extracting(FakeBillingApiClient.Call::command)
                .singleElement().isInstanceOfSatisfying(HoldCommand.class, hold -> {
                    assertThat(hold.referenceId()).isEqualTo(orderItemId);
                    // 보류 금액은 구매자 금액(가격 − 할인)이다
                    assertThat(hold.amountHp()).isEqualTo(PRICE_HP - SELLER_DISCOUNT);
                });
    }

    @Test
    void 구매자_금액이_0인_항목은_보류_작업을_등록하지_않는다() {
        Lecture free = saveOnSale();
        Lecture paid = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request,
                Map.of(free.getId(), PRICE_HP, paid.getId(), SELLER_DISCOUNT))));
        coupon.respond(request -> new CouponCallResult.Succeeded<>(reserved((CouponReserveRequest) request)));

        OrderResponse response = order(List.of(free, paid), PRICE_HP - SELLER_DISCOUNT);

        Long paidItemId = response.items().stream().filter(item -> item.lectureId().equals(paid.getId()))
                .findFirst().orElseThrow().orderItemId();
        assertThat(jdbcTemplate.queryForList("select order_item_id from lectures.lecture_hp_task where order_id = ? "
                + "and purpose = 'ORDER_HOLD'", Long.class, response.orderId())).containsExactly(paidItemId);
        assertThat(response.status()).isEqualTo(LectureOrderStatus.PAID);
    }

    @Test
    void 모든_항목이_0_HP인_쿠폰_주문은_지갑을_부르지_않고_예약_성공을_기록하는_트랜잭션에서_바로_결제_완료다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request,
                Map.of(lecture.getId(), PRICE_HP))));
        coupon.respond(request -> new CouponCallResult.Succeeded<>(reserved((CouponReserveRequest) request)));

        OrderResponse response = order(List.of(lecture), 0L);

        assertThat(response.status()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(response.paidAt()).isNotNull();
        assertThat(billing.calls()).isEmpty();
        assertThat(count("lectures.lecture_hp_task")).isZero();
        assertThat(workStatus(response.orderId())).isEqualTo("RESERVED");
    }

    @Test
    void 예약이_거절되면_작업은_거절이고_주문은_HP_작업_없이_결제_실패이며_409에_쿠폰_코드를_싣는다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request,
                Map.of(lecture.getId(), SELLER_DISCOUNT))));
        coupon.respond(request -> new CouponCallResult.Rejected<>(409, "COUPON_GENERATION_MISMATCH", 501L, null));

        assertThatThrownBy(() -> order(List.of(lecture), PRICE_HP - SELLER_DISCOUNT))
                .isInstanceOfSatisfying(LectureException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(LectureErrorCode.COUPON_RESERVATION_REJECTED);
                    assertThat(e.getProperties()).containsExactlyInAnyOrderEntriesOf(
                            Map.of("couponCode", "COUPON_GENERATION_MISMATCH", "memberCouponId", 501L));
                });

        Long orderId = onlyOrderId();
        assertThat(jdbcTemplate.queryForObject("select status from lectures.lecture_order where id = ?", String.class,
                orderId)).isEqualTo("HOLD_FAILED");
        assertThat(jdbcTemplate.queryForMap("select status, reject_code from lectures.order_coupon_work "
                + "where order_id = ?", orderId)).containsExactlyInAnyOrderEntriesOf(Map.of("status", "REJECTED",
                "reject_code", "COUPON_GENERATION_MISMATCH:501"));
        // 거절이면 쿠폰이 예약하지 않았으므로 해제 작업이 없다
        assertThat(jdbcTemplate.queryForObject("select count(*) from lectures.order_coupon_work where kind = 'RELEASE'",
                Long.class)).isZero();
        assertThat(count("lectures.lecture_hp_task")).isZero();
        assertThat(billing.calls()).isEmpty();
    }

    @Test
    void 예약을_보내지_못했거나_쿠폰이_인증을_거절하면_작업은_PENDING이고_결제_처리_중이다() {
        Lecture first = saveOnSale();
        Lecture second = saveOnSale();
        List<OrderResponse> responses = new ArrayList<>();
        for (CouponCallResult<?> result : List.<CouponCallResult<?>>of(new CouponCallResult.NotSent<>("회로 열림"),
                new CouponCallResult.Rejected<>(401, null, null, null))) {
            Lecture lecture = responses.isEmpty() ? first : second;
            coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request,
                    Map.of(lecture.getId(), SELLER_DISCOUNT))));
            coupon.respond(request -> result);
            responses.add(order(List.of(lecture), PRICE_HP - SELLER_DISCOUNT));
        }

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.status()).isEqualTo(LectureOrderStatus.HOLD_PENDING);
            assertThat(workStatus(response.orderId())).isEqualTo("PENDING");
        });
        assertThat(count("lectures.lecture_hp_task")).isZero();
    }

    @Test
    void 예약_응답이_할인_스냅샷과_다르면_작업은_예약됨이고_주문은_HP_작업_없이_결제_실패다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request,
                Map.of(lecture.getId(), SELLER_DISCOUNT))));
        coupon.respond(request -> new CouponCallResult.Succeeded<>(reserved((CouponReserveRequest) request,
                SELLER_DISCOUNT - 1)));

        assertThatThrownBy(() -> order(List.of(lecture), PRICE_HP - SELLER_DISCOUNT))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.ORDER_QUOTE_CHANGED);

        Long orderId = onlyOrderId();
        assertThat(jdbcTemplate.queryForObject("select status from lectures.lecture_order where id = ?", String.class,
                orderId)).isEqualTo("HOLD_FAILED");
        // 쿠폰은 예약된 채라 해제할 수 있게 예약 generation을 남기고 같은 트랜잭션에서 해제 작업을 만든다.
        // Fake 해제는 정한 결과가 없으면 결과 불명이라 PENDING으로 남는다
        assertThat(workStatus(orderId)).isEqualTo("RESERVED");
        assertThat(jdbcTemplate.queryForObject("select status from lectures.order_coupon_work where order_id = ? "
                + "and kind = 'RELEASE'", String.class, orderId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("select reserved_generation from lectures.order_item_coupon",
                Long.class)).isEqualTo(EXPECTED_GENERATION + 1);
        assertThat(count("lectures.lecture_hp_task")).isZero();
    }

    private OrderResponse order(List<Lecture> lectures, long expectedBuyerAmountHp) {
        List<OrderCouponSelection> selections = new ArrayList<>();
        for (int i = 0; i < lectures.size(); i++) {
            selections.add(new OrderCouponSelection(501L + i, lectures.get(i).getId()));
        }
        return lectureOrderFacade.createOrder(BUYER_ID, lectures.stream().map(Lecture::getId).toList(), selections,
                expectedBuyerAmountHp);
    }

    /** 강의마다 판매자 쿠폰 한 장(판매자 부담 = 정한 할인)을 쓴 계산 결과. 쿠폰은 요청 강의 순서대로 501, 502 */
    private static CouponCalculateResponse calculated(CouponCalculateRequest request, Map<Long, Long> discounts) {
        List<CouponCalculateResponse.Item> items = new ArrayList<>();
        long totalDiscount = 0;
        long totalBuyer = 0;
        for (CouponCalculateRequest.Selection selection : request.selections()) {
            CouponCalculateRequest.Item item = request.items().stream()
                    .filter(candidate -> candidate.lectureId().equals(selection.lectureId())).findFirst().orElseThrow();
            long discount = discounts.get(item.lectureId());
            items.add(new CouponCalculateResponse.Item(item.lectureId(), item.price(), discount, 0, discount,
                    item.price() - discount, item.price() - discount, List.of(new CouponCalculateResponse.Coupon(
                    selection.memberCouponId(), COUPON_ID, "SELLER", 1, item.price(), discount, 0, discount,
                    EXPECTED_GENERATION, 2))));
            totalDiscount += discount;
            totalBuyer += item.price() - discount;
        }
        return new CouponCalculateResponse(false, false, items, totalDiscount, totalBuyer);
    }

    private static CouponReserveResponse reserved(CouponReserveRequest request) {
        return reserved(request, null);
    }

    /** 요청을 그대로 예약한 응답. {@code sellerDiscountOverride}를 주면 항목 판매자 부담만 바꿔 스냅샷과 어긋나게 한다 */
    private static CouponReserveResponse reserved(CouponReserveRequest request, Long sellerDiscountOverride) {
        List<CouponReserveResponse.Item> items = request.items().stream()
                .filter(item -> !item.coupons().isEmpty())
                .map(item -> {
                    long discount = item.coupons().stream().mapToLong(CouponReserveRequest.Coupon::discount).sum();
                    long seller = item.coupons().stream().mapToLong(CouponReserveRequest.Coupon::sellerAmount).sum();
                    return new CouponReserveResponse.Item(item.orderItemId(), discount, discount - seller,
                            sellerDiscountOverride == null ? seller : sellerDiscountOverride,
                            item.coupons().stream().map(coupon -> new CouponReserveResponse.Coupon(
                                    coupon.memberCouponId(), COUPON_ID, coupon.expectedGeneration() + 1,
                                    coupon.applyOrder(), item.price(), coupon.discount(), coupon.platformAmount(),
                                    coupon.sellerAmount(), coupon.policyVersion())).toList());
                })
                .toList();
        return new CouponReserveResponse(request.workId(), "RESERVED", items);
    }

    private String workStatus(Long orderId) {
        return jdbcTemplate.queryForObject("select status from lectures.order_coupon_work where order_id = ? "
                + "and kind = 'RESERVE'", String.class, orderId);
    }

    private Long onlyOrderId() {
        return jdbcTemplate.queryForObject("select id from lectures.lecture_order", Long.class);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private Lecture saveOnSale() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }
}
