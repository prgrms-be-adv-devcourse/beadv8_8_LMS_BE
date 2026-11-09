package com.hapbang.lecture.domain;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

// 주문 항목의 할인 스냅샷과 구매자 금액(P − D)·판매자 지급액(P − S)
class OrderItemDiscountTest {

    @Test
    void 할인이_없으면_구매자_금액과_판매자_지급액은_가격이다() {
        OrderItem item = placeOne();

        assertThat(item.getDiscountHp()).isZero();
        assertThat(item.buyerAmountHp()).isEqualTo(PRICE_HP);
        assertThat(item.sellerPayoutHp()).isEqualTo(PRICE_HP);
    }

    @Test
    void 구매자_금액은_가격에서_할인_합을_빼고_판매자_지급액은_판매자_부담만_뺀다() {
        OrderItem item = placeOne();

        item.applyDiscount(3_000L, 1_000L, 2_000L);

        assertThat(item.buyerAmountHp()).isEqualTo(PRICE_HP - 3_000L);
        assertThat(item.sellerPayoutHp()).isEqualTo(PRICE_HP - 2_000L);
    }

    @Test
    void 판매자_쿠폰으로_전액_할인하면_구매자_금액과_판매자_지급액이_0이다() {
        OrderItem item = placeOne();

        item.applyDiscount(PRICE_HP, 0L, PRICE_HP);

        assertThat(item.buyerAmountHp()).isZero();
        assertThat(item.sellerPayoutHp()).isZero();
    }

    @Test
    void 할인_합이_부담_몫의_합과_다르면_거부한다() {
        OrderItem item = placeOne();

        assertThatThrownBy(() -> item.applyDiscount(3_000L, 1_000L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(item.getDiscountHp()).isZero();
    }

    @Test
    void 부담_몫이_음수이거나_할인_합이_가격보다_크면_거부한다() {
        OrderItem item = placeOne();

        assertThatThrownBy(() -> item.applyDiscount(1_000L, -1_000L, 2_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item.applyDiscount(PRICE_HP + 1, 0L, PRICE_HP + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 쿠폰_한_장의_할인이_부담_몫의_합과_다르거나_남은_금액보다_크면_거부한다() {
        assertThat(coupon(5_000L, 3_000L, 0L, 3_000L).getSellerAmountHp()).isEqualTo(3_000L);
        assertThatThrownBy(() -> coupon(5_000L, 3_000L, 1_000L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coupon(5_000L, 6_000L, 0L, 6_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coupon(5_000L, 1_000L, -1_000L, 2_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OrderItemCoupon coupon(long base, long discount, long platform, long seller) {
        return OrderItemCoupon.create(1L, 11L, 21L, "SELLER", 1, base, discount, platform, seller, 3L, 1);
    }

    private static OrderItem placeOne() {
        return LectureOrder.place(OTHER_USER_ID, List.of(withId(onSaleLecture(), 10L)), Set.of(), NOW)
                .getItems().getFirst();
    }
}
