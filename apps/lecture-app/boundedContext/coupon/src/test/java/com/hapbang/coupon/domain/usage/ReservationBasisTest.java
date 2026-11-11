package com.hapbang.coupon.domain.usage;

import static com.hapbang.coupon.domain.usage.UsageFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.coupon.domain.usage.ReservationBasis.Check;
import com.hapbang.coupon.domain.usage.ReservationBasis.Mismatch;
import com.hapbang.coupon.domain.usage.ReservationBasis.RequestedCoupon;

// 예약 요청의 근거(세대·정책 버전·적용 순서·금액)를 다시 계산한 결과와 대조한다
class ReservationBasisTest {

    private static final long LECTURE = 7L;

    // 판매자 10%(1,000) → 플랫폼 10%(900). 판매자 세대 2·정책 버전 1, 플랫폼 세대 0·정책 버전 3
    private final ItemResult recomputed = new CouponCalculator().apply(item(LECTURE, 10_000),
            List.of(seller(1, LECTURE).generation(2).build(), platformAny(2).policyVersion(3).build()),
            MEMBER_ID, NOW);

    private static RequestedCoupon sellerBasis() {
        return new RequestedCoupon(1, 2, 1, 1, 1_000, 0, 1_000);
    }

    private static RequestedCoupon platformBasis() {
        return new RequestedCoupon(2, 0, 3, 2, 900, 900, 0);
    }

    @Test
    void 근거가_다시_계산한_값과_같으면_일치다() {
        // 요청의 쿠폰 순서는 관계없다
        Check check = ReservationBasis.compare(List.of(platformBasis(), sellerBasis()), recomputed);

        assertThat(check.matched()).isTrue();
        assertThat(check.memberCouponId()).isNull();
    }

    @Test
    void 기대_세대가_다르면_세대_불일치다() {
        Check check = ReservationBasis.compare(
                List.of(new RequestedCoupon(1, 1, 1, 1, 1_000, 0, 1_000), platformBasis()), recomputed);

        assertThat(check).isEqualTo(new Check(Mismatch.GENERATION, 1L));
    }

    @Test
    void 정책_버전이_다르면_정책_버전_변경이다() {
        Check check = ReservationBasis.compare(
                List.of(sellerBasis(), new RequestedCoupon(2, 0, 2, 2, 900, 900, 0)), recomputed);

        assertThat(check).isEqualTo(new Check(Mismatch.POLICY_VERSION, 2L));
    }

    @Test
    void 할인_부담_금액이나_적용_순서가_다르면_금액_불일치다() {
        assertThat(ReservationBasis.compare(
                List.of(sellerBasis(), new RequestedCoupon(2, 0, 3, 2, 1_000, 1_000, 0)), recomputed))
                .isEqualTo(new Check(Mismatch.AMOUNT, 2L));
        assertThat(ReservationBasis.compare(
                List.of(new RequestedCoupon(1, 2, 1, 2, 1_000, 0, 1_000), platformBasis()), recomputed))
                .isEqualTo(new Check(Mismatch.AMOUNT, 1L));
        assertThat(ReservationBasis.compare(
                List.of(new RequestedCoupon(1, 2, 1, 1, 1_000, 1_000, 0), platformBasis()), recomputed))
                .isEqualTo(new Check(Mismatch.AMOUNT, 1L));
    }

    @Test
    void 한_장_안에서는_세대를_정책_버전과_금액보다_먼저_본다() {
        Check check = ReservationBasis.compare(
                List.of(new RequestedCoupon(1, 9, 9, 9, 9, 9, 0), platformBasis()), recomputed);

        assertThat(check.mismatch()).isEqualTo(Mismatch.GENERATION);
    }

    @Test
    void 쿠폰_집합이_다르면_금액_불일치다() {
        assertThat(ReservationBasis.compare(List.of(sellerBasis()), recomputed))
                .isEqualTo(new Check(Mismatch.AMOUNT, 2L));
        assertThat(ReservationBasis.compare(
                List.of(sellerBasis(), platformBasis(), new RequestedCoupon(3, 0, 1, 3, 0, 0, 0)), recomputed))
                .isEqualTo(new Check(Mismatch.AMOUNT, 3L));
    }
}
