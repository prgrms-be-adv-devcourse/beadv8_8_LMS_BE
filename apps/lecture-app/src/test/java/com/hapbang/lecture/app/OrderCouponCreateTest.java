package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

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
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 쿠폰 주문 0~1단계(트랜잭션 밖 쿠폰 할인 계산 → 주문·할인 스냅샷·쿠폰 계산 근거·쿠폰 예약 작업 저장)를 Fake 쿠폰 Client와 DB로 확인한다.
 * 쿠폰 Client의 상태 변환은 CouponClientTest가, 할인 합 규칙은 OrderItemDiscountTest가 맡는다.
 */
@IntegrationTest
@WithMockUser(roles = "USER")
class OrderCouponCreateTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final long SELLER_DISCOUNT = 3_000L;

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
    void 쿠폰을_고르지_않은_주문은_쿠폰_API를_부르지_않고_지금처럼_결제한다() {
        Lecture lecture = saveOnSale();

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()), List.of(), null);

        assertThat(response.status()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(coupon.calls()).isEmpty();
        assertThat(billing.calls()).hasSize(1);
        assertThat(count("lectures.order_coupon_work")).isZero();
        assertThat(jdbcTemplate.queryForObject("select discount_hp from lectures.order_item where order_id = ?",
                Long.class, response.orderId())).isZero();
    }

    @Test
    void 쿠폰_주문은_트랜잭션_밖에서_계산하고_할인_스냅샷과_계산_근거와_예약_작업을_저장하며_예약_결과를_모르면_HP_작업이_없다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(sellerCoupon((CouponCalculateRequest) request)));

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()),
                List.of(new OrderCouponSelection(501L, lecture.getId())), PRICE_HP - SELLER_DISCOUNT);

        // Fake의 예약은 정한 결과가 없으면 결과 불명이다. 예약 결과를 모르니 결제 처리 중이다
        assertThat(response.status()).isEqualTo(LectureOrderStatus.HOLD_PENDING);
        assertThat(coupon.calls()).hasSize(2).allSatisfy(call -> assertThat(call.transactionActive()).isFalse());
        assertThat(coupon.calls().getFirst().request()).isEqualTo(new CouponCalculateRequest(BUYER_ID,
                List.of(new CouponCalculateRequest.Item(lecture.getId(), SELLER_ID, PRICE_HP)),
                List.of(new CouponCalculateRequest.Selection(501L, lecture.getId()))));
        Long orderItemId = response.items().getFirst().orderItemId();
        assertThat(jdbcTemplate.queryForMap("select discount_hp, platform_discount_hp, seller_discount_hp "
                + "from lectures.order_item where id = ?", orderItemId))
                .containsExactlyInAnyOrderEntriesOf(Map.of("discount_hp", SELLER_DISCOUNT, "platform_discount_hp", 0L,
                        "seller_discount_hp", SELLER_DISCOUNT));
        assertThat(jdbcTemplate.queryForMap("select member_coupon_id, coupon_id, coupon_type, apply_order, "
                + "base_amount_hp, discount_hp, seller_amount_hp, expected_generation, reserved_generation, "
                + "policy_version from lectures.order_item_coupon where order_item_id = ?", orderItemId))
                .containsAllEntriesOf(Map.of("member_coupon_id", 501L, "coupon_id", 11L, "coupon_type", "SELLER",
                        "apply_order", 1, "base_amount_hp", PRICE_HP, "discount_hp", SELLER_DISCOUNT,
                        "seller_amount_hp", SELLER_DISCOUNT, "expected_generation", 4L, "policy_version", 2))
                .containsEntry("reserved_generation", null);
        assertThat(jdbcTemplate.queryForMap("select kind, work_id, status, attempt_count "
                + "from lectures.order_coupon_work where order_id = ?", response.orderId()))
                .containsExactlyInAnyOrderEntriesOf(Map.of("kind", "RESERVE",
                        "work_id", "lecture-order:" + response.orderId() + ":reserve", "status", "PENDING",
                        "attempt_count", 1));
        assertThat(jdbcTemplate.queryForObject("select count(*) from lectures.lecture_hp_task where order_id = ?",
                Long.class, response.orderId())).isZero();
        assertThat(billing.calls()).isEmpty();
    }

    @Test
    void 쿠폰_계산이_거절되면_쿠폰_오류_코드를_싣고_409이며_주문하지_않는다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Rejected<>(409, "COUPON_NOT_USABLE", 501L, "사용할 수 없습니다."));

        assertThatThrownBy(() -> orderWithCoupon(lecture, PRICE_HP - SELLER_DISCOUNT))
                .isInstanceOfSatisfying(LectureException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(LectureErrorCode.COUPON_CALCULATION_REJECTED);
                    assertThat(e.getProperties()).containsExactlyInAnyOrderEntriesOf(
                            Map.of("couponCode", "COUPON_NOT_USABLE", "memberCouponId", 501L));
                });
        assertNoOrder();
    }

    @Test
    void 쿠폰_계산을_보내지_못했거나_결과를_모르면_503이고_주문하지_않는다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.NotSent<>("회로 열림"));
        coupon.respond(request -> new CouponCallResult.Unknown<>(0, "응답 시간 초과"));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> orderWithCoupon(lecture, PRICE_HP - SELLER_DISCOUNT))
                    .isInstanceOf(LectureException.class)
                    .extracting("errorCode").isEqualTo(LectureErrorCode.COUPON_UNAVAILABLE);
        }
        assertNoOrder();
    }

    @Test
    void 쿠폰이_인증이나_권한을_거절하면_설정_문제라_503이고_주문하지_않는다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Rejected<>(401, null, null, null));
        coupon.respond(request -> new CouponCallResult.Rejected<>(403, null, null, null));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> orderWithCoupon(lecture, PRICE_HP - SELLER_DISCOUNT))
                    .isInstanceOf(LectureException.class)
                    .extracting("errorCode").isEqualTo(LectureErrorCode.COUPON_UNAVAILABLE);
        }
        assertNoOrder();
    }

    @Test
    void 쿠폰_계산_응답의_할인이_플랫폼과_판매자_몫의_합과_다르면_쿠폰_응답_불량이라_503이고_주문하지_않는다() {
        Lecture lecture = saveOnSale();
        // 항목 할인 3,000인데 몫의 합은 2,000
        coupon.respond(request -> new CouponCallResult.Succeeded<>(withCoupon((CouponCalculateRequest) request,
                new CouponCalculateResponse.Coupon(501L, 11L, "SELLER", 1, PRICE_HP, SELLER_DISCOUNT, 0L, 2_000L, 4L,
                        2), SELLER_DISCOUNT)));

        assertThatThrownBy(() -> orderWithCoupon(lecture, PRICE_HP - SELLER_DISCOUNT))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.COUPON_UNAVAILABLE);
        assertNoOrder();
    }

    @Test
    void 항목_금액은_맞고_쿠폰_한_장의_몫이_넘쳐_합이_맞아_보여도_쿠폰_응답_불량이라_503이고_주문하지_않는다() {
        Lecture lecture = saveOnSale();
        // 항목은 판매자 3,000으로 맞고, 쿠폰 한 장만 플랫폼 MAX + 판매자 1 = MIN(넘침)으로 할인과 같아 보인다
        CouponCalculateResponse.Coupon overflowed = new CouponCalculateResponse.Coupon(501L, 11L, "SELLER", 1,
                PRICE_HP, Long.MIN_VALUE, Long.MAX_VALUE, 1L, 4L, 2);
        coupon.respond(request -> {
            CouponCalculateRequest.Item first = ((CouponCalculateRequest) request).items().getFirst();
            long buyerAmount = first.price() - SELLER_DISCOUNT;
            CouponCalculateResponse.Item item = new CouponCalculateResponse.Item(first.lectureId(), first.price(),
                    SELLER_DISCOUNT, 0L, SELLER_DISCOUNT, buyerAmount, buyerAmount, List.of(overflowed));
            return new CouponCallResult.Succeeded<>(new CouponCalculateResponse(false, false, List.of(item),
                    SELLER_DISCOUNT, buyerAmount));
        });

        assertThatThrownBy(() -> orderWithCoupon(lecture, PRICE_HP - SELLER_DISCOUNT))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.COUPON_UNAVAILABLE);
        assertNoOrder();
    }

    @Test
    void 쿠폰_계산_응답의_할인이_가격보다_크면_쿠폰_응답_불량이라_503이고_주문하지_않는다() {
        Lecture lecture = saveOnSale();
        long overDiscount = PRICE_HP + 1;
        coupon.respond(request -> new CouponCallResult.Succeeded<>(withCoupon((CouponCalculateRequest) request,
                new CouponCalculateResponse.Coupon(501L, 11L, "SELLER", 1, PRICE_HP, overDiscount, 0L, overDiscount,
                        4L, 2))));

        assertThatThrownBy(() -> orderWithCoupon(lecture, -1L))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.COUPON_UNAVAILABLE);
        assertNoOrder();
    }

    @Test
    void 다시_계산한_결제_금액이_구매자가_본_금액과_다르면_409_ORDER_QUOTE_CHANGED이고_주문하지_않는다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(sellerCoupon((CouponCalculateRequest) request)));

        assertThatThrownBy(() -> orderWithCoupon(lecture, PRICE_HP))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.ORDER_QUOTE_CHANGED);
        assertNoOrder();
    }

    @Test
    void 플랫폼_부담_할인이_있으면_409_COUPON_PLATFORM_FUNDING_UNAVAILABLE이고_주문하지_않는다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(platformCoupon((CouponCalculateRequest) request)));

        assertThatThrownBy(() -> orderWithCoupon(lecture, PRICE_HP - 1_000L))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.COUPON_PLATFORM_FUNDING_UNAVAILABLE);
        assertNoOrder();
    }

    @Test
    void 주문할_수_없는_강의는_쿠폰_API를_부르지_않고_쿠폰_없는_주문과_같은_오류다() {
        Lecture own = lectureRepository.save(approved(Lecture.register(BUYER_ID, TITLE, DESCRIPTION, PRICE_HP, null,
                contentSpecs(), NOW)));

        assertThatThrownBy(() -> orderWithCoupon(own, PRICE_HP))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.OWN_LECTURE_NOT_PURCHASABLE);
        assertThat(coupon.calls()).isEmpty();
    }

    private OrderResponse orderWithCoupon(Lecture lecture, long expectedBuyerAmountHp) {
        return lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()),
                List.of(new OrderCouponSelection(501L, lecture.getId())), expectedBuyerAmountHp);
    }

    private void assertNoOrder() {
        assertThat(count("lectures.lecture_order")).isZero();
        assertThat(count("lectures.order_coupon_work")).isZero();
        assertThat(billing.calls()).isEmpty();
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    /** 첫 강의에 판매자 쿠폰 한 장(판매자 부담 3,000 HP) */
    private static CouponCalculateResponse sellerCoupon(CouponCalculateRequest request) {
        return withCoupon(request, new CouponCalculateResponse.Coupon(501L, 11L, "SELLER", 1,
                request.items().getFirst().price(), SELLER_DISCOUNT, 0L, SELLER_DISCOUNT, 4L, 2));
    }

    /** 첫 강의에 플랫폼 쿠폰 한 장(플랫폼 부담 1,000 HP) */
    private static CouponCalculateResponse platformCoupon(CouponCalculateRequest request) {
        return withCoupon(request, new CouponCalculateResponse.Coupon(502L, 12L, "PLATFORM", 1,
                request.items().getFirst().price(), 1_000L, 1_000L, 0L, 0L, 1));
    }

    private static CouponCalculateResponse withCoupon(CouponCalculateRequest request,
                                                      CouponCalculateResponse.Coupon applied) {
        return withCoupon(request, applied, applied.discount());
    }

    /** 항목 할인을 따로 정한다(금액이 맞지 않는 쿠폰 응답을 만들 때) */
    private static CouponCalculateResponse withCoupon(CouponCalculateRequest request,
                                                      CouponCalculateResponse.Coupon applied, long itemDiscount) {
        CouponCalculateRequest.Item first = request.items().getFirst();
        long buyerAmount = first.price() - itemDiscount;
        return new CouponCalculateResponse(false, false, List.of(new CouponCalculateResponse.Item(first.lectureId(),
                first.price(), itemDiscount, applied.platformAmount(), applied.sellerAmount(), buyerAmount,
                first.price() - applied.sellerAmount(), List.of(applied))), itemDiscount, buyerAmount);
    }

    private Lecture saveOnSale() {
        return lectureRepository.save(approved(Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null,
                contentSpecs(), NOW)));
    }

    private static Lecture approved(Lecture lecture) {
        lecture.approve(ADMIN_ID, NOW);
        return lecture;
    }
}
