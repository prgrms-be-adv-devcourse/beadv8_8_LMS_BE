package com.hapbang.lecture.domain;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.shared.coupon.api.CouponReserveResponse;

// 쿠폰 예약 응답과 주문 할인 스냅샷의 대조, 예약 generation 기록
class OrderCouponReservationTest {

    private static final long ITEM_ID = 31L;
    private static final long FREE_ITEM_ID = 32L;

    private OrderItem item;
    private OrderItem noCouponItem;
    private OrderItemCoupon seller;
    private OrderItemCoupon platform;

    @BeforeEach
    void setUp() {
        LectureOrder order = LectureOrder.place(OTHER_USER_ID,
                List.of(withId(onSaleLecture(), 10L), withId(onSaleLecture(), 20L)), Set.of(), NOW);
        item = order.getItems().get(0);
        noCouponItem = order.getItems().get(1);
        ReflectionTestUtils.setField(item, "id", ITEM_ID);
        ReflectionTestUtils.setField(noCouponItem, "id", FREE_ITEM_ID);
        item.applyDiscount(4_000L, 1_000L, 3_000L);
        seller = OrderItemCoupon.create(ITEM_ID, 501L, 11L, "SELLER", 1, PRICE_HP, 3_000L, 0L, 3_000L, 4L, 2);
        platform = OrderItemCoupon.create(ITEM_ID, 502L, 12L, "PLATFORM", 2, PRICE_HP - 3_000L, 1_000L, 1_000L, 0L,
                0L, 1);
    }

    @Test
    void 쿠폰을_쓴_항목의_할인과_쿠폰별_근거가_같으면_어긋남이_없다() {
        assertThat(mismatch(response(itemOf(4_000L, 1_000L, 3_000L, platformCoupon(1_000L), sellerCoupon(1)))))
                .isEmpty();
    }

    @Test
    void 항목_할인이나_부담_몫이_다르면_어긋난다() {
        assertThat(mismatch(response(itemOf(4_000L, 0L, 4_000L, sellerCoupon(1), platformCoupon(1_000L)))))
                .hasValueSatisfying(reason -> assertThat(reason).contains("항목 할인"));
    }

    @Test
    void 쿠폰_한_장의_금액이나_적용_순서가_다르면_어긋난다() {
        assertThat(mismatch(response(itemOf(4_000L, 1_000L, 3_000L, sellerCoupon(1), platformCoupon(999L)))))
                .hasValueSatisfying(reason -> assertThat(reason).contains("memberCouponId=502"));
        assertThat(mismatch(response(itemOf(4_000L, 1_000L, 3_000L, sellerCoupon(3), platformCoupon(1_000L)))))
                .hasValueSatisfying(reason -> assertThat(reason).contains("memberCouponId=501"));
    }

    @Test
    void 쿠폰을_쓴_항목이_응답에_없거나_쿠폰을_쓰지_않은_항목이_응답에_있으면_어긋난다() {
        assertThat(mismatch(response())).hasValueSatisfying(reason -> assertThat(reason).contains("응답에 없다"));
        assertThat(mismatch(response(itemOf(4_000L, 1_000L, 3_000L, sellerCoupon(1), platformCoupon(1_000L)),
                new CouponReserveResponse.Item(FREE_ITEM_ID, 0, 0, 0, List.of()))))
                .hasValueSatisfying(reason -> assertThat(reason).contains("쿠폰을 쓰지 않은 항목"));
    }

    @Test
    void 예약_generation은_같은_값이_다시_오면_그대로이고_다른_값이면_거부한다() {
        seller.recordReserved(5L);
        seller.recordReserved(5L);

        assertThat(seller.getReservedGeneration()).isEqualTo(5L);
        assertThatThrownBy(() -> seller.recordReserved(6L)).isInstanceOf(IllegalStateException.class);
    }

    private Optional<String> mismatch(CouponReserveResponse response) {
        return OrderCouponReservation.mismatch(List.of(item, noCouponItem), List.of(seller, platform), response);
    }

    private static CouponReserveResponse response(CouponReserveResponse.Item... items) {
        return new CouponReserveResponse("lecture-order:1:reserve", "RESERVED", List.of(items));
    }

    private static CouponReserveResponse.Item itemOf(long discount, long platformDiscount, long sellerDiscount,
                                                     CouponReserveResponse.Coupon... coupons) {
        return new CouponReserveResponse.Item(ITEM_ID, discount, platformDiscount, sellerDiscount, List.of(coupons));
    }

    private static CouponReserveResponse.Coupon sellerCoupon(int applyOrder) {
        return new CouponReserveResponse.Coupon(501L, 11L, 5L, applyOrder, PRICE_HP, 3_000L, 0L, 3_000L, 2);
    }

    private static CouponReserveResponse.Coupon platformCoupon(long discount) {
        return new CouponReserveResponse.Coupon(502L, 12L, 1L, 2, PRICE_HP - 3_000L, discount, discount, 0L, 1);
    }
}
