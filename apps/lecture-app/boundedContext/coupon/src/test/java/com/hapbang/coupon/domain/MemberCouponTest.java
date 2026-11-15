package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MemberCouponTest {

    private static final Instant EXPIRES_AT = NOW.plusSeconds(3_600);
    private static final long ORDER_ITEM_ID = 77L;

    private static MemberCoupon issued() {
        return MemberCoupon.issue(MEMBER_ID, 5L, "req-1", NOW, EXPIRES_AT, NOW);
    }

    /** 사용 전이로 그 상태를 만든다. EXPIRED는 만료 뒤 예약 해제로 만든다 */
    private static MemberCoupon inStatus(MemberCouponStatus status) {
        MemberCoupon coupon = issued();
        switch (status) {
            case AVAILABLE -> {
            }
            case RESERVED -> coupon.reserve(MEMBER_ID, ORDER_ITEM_ID, NOW);
            case USED -> {
                coupon.reserve(MEMBER_ID, ORDER_ITEM_ID, NOW);
                coupon.markUsed(NOW);
            }
            case EXPIRED -> {
                coupon.reserve(MEMBER_ID, ORDER_ITEM_ID, NOW);
                coupon.releaseReservation(EXPIRES_AT);
            }
        }
        assertThat(coupon.getStatus()).isEqualTo(status);
        return coupon;
    }

    private static void assertRejected(ThrowableAssert.ThrowingCallable call, CouponDomainError error) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(CouponDomainException.class,
                        exception -> assertThat(exception.getError()).isEqualTo(error));
    }

    @Test
    void 발급_쿠폰은_AVAILABLE_세대_0으로_시작한다() {
        MemberCoupon coupon = issued();

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(coupon.getGeneration()).isZero();
        assertThat(coupon.getIssuedAt()).isEqualTo(NOW);
        assertThat(coupon.getExpiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void 만료_시각_전까지만_사용할_수_있다() {
        MemberCoupon coupon = issued();

        assertThat(coupon.isUsableAt(EXPIRES_AT.minusNanos(1))).isTrue();
        assertThat(coupon.isUsableAt(EXPIRES_AT)).isFalse();
    }

    // 예약 ---------------------------------------------------------------------------------------------------

    @Test
    void 예약하면_RESERVED가_되고_세대가_오르며_주문_항목과_예약_시각을_남긴다() {
        MemberCoupon coupon = issued();

        coupon.reserve(MEMBER_ID, ORDER_ITEM_ID, NOW);

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.RESERVED);
        assertThat(coupon.getGeneration()).isEqualTo(1);
        assertThat(coupon.getCurrentOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(coupon.getReservedAt()).isEqualTo(NOW);
    }

    @Test
    void 남의_쿠폰이나_만료된_쿠폰은_예약할_수_없다() {
        assertRejected(() -> issued().reserve(OTHER_MEMBER_ID, ORDER_ITEM_ID, NOW), CouponDomainError.COUPON_NOT_USABLE);
        assertRejected(() -> issued().reserve(MEMBER_ID, ORDER_ITEM_ID, EXPIRES_AT), CouponDomainError.COUPON_NOT_USABLE);
    }

    @ParameterizedTest
    @EnumSource(value = MemberCouponStatus.class, names = {"RESERVED", "USED", "EXPIRED"})
    void AVAILABLE이_아니면_예약할_수_없다(MemberCouponStatus status) {
        MemberCoupon coupon = inStatus(status);

        assertRejected(() -> coupon.reserve(MEMBER_ID, ORDER_ITEM_ID, NOW), CouponDomainError.COUPON_NOT_USABLE);
    }

    // 예약 해제 ------------------------------------------------------------------------------------------------

    @Test
    void 만료_전_예약_해제는_AVAILABLE로_돌아가고_세대는_그대로다() {
        MemberCoupon coupon = inStatus(MemberCouponStatus.RESERVED);

        MemberCouponStatus released = coupon.releaseReservation(EXPIRES_AT.minusNanos(1_000));

        assertThat(released).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(coupon.getGeneration()).isEqualTo(1);
        assertThat(coupon.getCurrentOrderItemId()).isNull();
        assertThat(coupon.getReservedAt()).isNull();
    }

    @Test
    void 만료_시각이_지나면_예약_해제는_EXPIRED로_끝난다() {
        MemberCoupon coupon = inStatus(MemberCouponStatus.RESERVED);

        assertThat(coupon.releaseReservation(EXPIRES_AT)).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(coupon.getCurrentOrderItemId()).isNull();
    }

    @Test
    void 해제한_쿠폰을_다시_예약하면_새_사용_시도라_세대가_또_오른다() {
        MemberCoupon coupon = inStatus(MemberCouponStatus.RESERVED);
        coupon.releaseReservation(NOW);

        coupon.reserve(MEMBER_ID, 78L, NOW);

        assertThat(coupon.getGeneration()).isEqualTo(2);
        assertThat(coupon.getCurrentOrderItemId()).isEqualTo(78L);
    }

    @ParameterizedTest
    @EnumSource(value = MemberCouponStatus.class, names = {"AVAILABLE", "USED", "EXPIRED"})
    void RESERVED가_아니면_예약을_해제할_수_없다(MemberCouponStatus status) {
        MemberCoupon coupon = inStatus(status);

        assertRejected(() -> coupon.releaseReservation(NOW), CouponDomainError.INVALID_STATUS_TRANSITION);
    }

    // 사용 완료·복원 --------------------------------------------------------------------------------------------

    @Test
    void 예약한_쿠폰은_사용_완료할_수_있다() {
        MemberCoupon coupon = inStatus(MemberCouponStatus.RESERVED);

        coupon.markUsed(NOW.plusSeconds(1));

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.USED);
        assertThat(coupon.getUsedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(coupon.getGeneration()).isEqualTo(1);
    }

    /** 할인은 예약 성공 시점 기준으로 인정한다. 결제 중 기한이 지나도 사용 완료로 끝낸다 */
    @Test
    void 예약_뒤_기한이_지나도_결제_완료로_사용_완료한다() {
        MemberCoupon coupon = inStatus(MemberCouponStatus.RESERVED);

        coupon.markUsed(EXPIRES_AT.plusSeconds(1));

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.USED);
    }

    @ParameterizedTest
    @EnumSource(value = MemberCouponStatus.class, names = {"AVAILABLE", "USED", "EXPIRED"})
    void RESERVED가_아니면_사용_완료할_수_없다(MemberCouponStatus status) {
        MemberCoupon coupon = inStatus(status);

        assertRejected(() -> coupon.markUsed(NOW), CouponDomainError.INVALID_STATUS_TRANSITION);
    }

    @Test
    void 사용_완료한_쿠폰은_만료_전이면_AVAILABLE_뒤면_EXPIRED로_복원한다() {
        MemberCoupon beforeExpiry = inStatus(MemberCouponStatus.USED);
        MemberCoupon afterExpiry = inStatus(MemberCouponStatus.USED);

        assertThat(beforeExpiry.restore(NOW)).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(beforeExpiry.getUsedAt()).isNull();
        assertThat(beforeExpiry.getCurrentOrderItemId()).isNull();
        assertThat(beforeExpiry.getGeneration()).isEqualTo(1);
        assertThat(afterExpiry.restore(EXPIRES_AT)).isEqualTo(MemberCouponStatus.EXPIRED);
    }

    /** 결제 완료보다 먼저 온 취소 완료는 예약 중인 쿠폰을 복원한다 */
    @Test
    void 예약한_쿠폰도_취소_완료로_복원한다() {
        MemberCoupon coupon = inStatus(MemberCouponStatus.RESERVED);

        assertThat(coupon.restore(NOW)).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(coupon.getReservedAt()).isNull();
        assertThat(coupon.getCurrentOrderItemId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = MemberCouponStatus.class, names = {"AVAILABLE", "EXPIRED"})
    void RESERVED나_USED가_아니면_복원할_수_없다(MemberCouponStatus status) {
        MemberCoupon coupon = inStatus(status);

        assertRejected(() -> coupon.restore(NOW), CouponDomainError.INVALID_STATUS_TRANSITION);
    }

    // 만료 -----------------------------------------------------------------------------------------------------

    @Test
    void 기한이_지났다는_판정은_만료_시각과_같은_때부터다() {
        assertThat(MemberCoupon.isExpiredAt(EXPIRES_AT, EXPIRES_AT.minusNanos(1))).isFalse();
        assertThat(MemberCoupon.isExpiredAt(EXPIRES_AT, EXPIRES_AT)).isTrue();
        assertThat(MemberCoupon.isExpiredAt(EXPIRES_AT, EXPIRES_AT.plusSeconds(1))).isTrue();
        // 해제·복원 때 돌아갈 상태와 같은 경계다
        assertThat(MemberCoupon.returnStatusOf(EXPIRES_AT, EXPIRES_AT)).isEqualTo(MemberCouponStatus.EXPIRED);
    }

    @Test
    void 기한이_지난_미사용_쿠폰은_만료_시각부터_EXPIRED로_바꾸고_세대는_그대로다() {
        MemberCoupon coupon = issued();

        assertRejected(() -> coupon.expire(EXPIRES_AT.minusNanos(1)), CouponDomainError.INVALID_STATUS_TRANSITION);
        coupon.expire(EXPIRES_AT);

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(coupon.getGeneration()).isZero();
        assertThat(coupon.getUpdatedAt()).isEqualTo(EXPIRES_AT);
    }

    @ParameterizedTest
    @EnumSource(value = MemberCouponStatus.class, names = {"RESERVED", "USED", "EXPIRED"})
    void 미사용이_아니면_기한이_지나도_만료하지_않는다(MemberCouponStatus status) {
        MemberCoupon coupon = inStatus(status);

        assertRejected(() -> coupon.expire(EXPIRES_AT.plusSeconds(1)), CouponDomainError.INVALID_STATUS_TRANSITION);
    }
}
