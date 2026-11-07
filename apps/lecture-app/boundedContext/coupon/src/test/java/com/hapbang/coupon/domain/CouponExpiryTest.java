package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class CouponExpiryTest {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-10T14:59:59.123Z");

    @Test
    void 접수_기준_만료는_최초_접수_시각에_N일을_더한다() {
        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().relative(30).build(), NOW);

        assertThat(coupon.expiresAtFor(ACCEPTED_AT)).isEqualTo(ACCEPTED_AT.plus(Duration.ofDays(30)));
    }

    @Test
    void 고정_만료는_접수_시각과_무관하게_고정_시각이다() {
        Instant fixed = Instant.parse("2026-12-31T14:59:59Z");
        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().fixed(fixed).build(), NOW);

        assertThat(coupon.expiresAtFor(ACCEPTED_AT)).isEqualTo(fixed);
        assertThat(coupon.expiresAtFor(ACCEPTED_AT.plusSeconds(3_600))).isEqualTo(fixed);
    }

    @Test
    void 같은_최초_접수_시각이면_다시_계산해도_같은_만료_시각이다() {
        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().relative(7).build(), NOW);

        assertThat(coupon.expiresAtFor(ACCEPTED_AT)).isEqualTo(coupon.expiresAtFor(Instant.parse(ACCEPTED_AT.toString())));
    }
}
