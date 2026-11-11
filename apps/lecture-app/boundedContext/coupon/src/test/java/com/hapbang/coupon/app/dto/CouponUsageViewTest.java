package com.hapbang.coupon.app.dto;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.UsageItem;

// 사용 시도 → 회원 이력 한 건 변환: 상태 넷에서 돌려받은 상태·시각을 해제·복원 중 어느 갈래에서 고르는지
class CouponUsageViewTest {

    private static final Instant PAID_AT = NOW.plusSeconds(10);
    private static final Instant RETURNED_AT = NOW.plusSeconds(20);
    private static final Coupon COUPON = sellerCoupon();

    private static CouponUsage reserved() {
        CouponUsage usage = CouponUsage.reserve(MEMBER_ID, 900L, new UsageItem(901L, LECTURE_ID, SELLER_ID, 10_000),
                new AppliedCoupon(31L, 5L, CouponType.SELLER, 1, 10_000, 1_000, 0, 1_000, 0, 1),
                "lecture-order:900:reserve", NOW);
        ReflectionTestUtils.setField(usage, "id", 77L);
        return usage;
    }

    @Test
    void 예약_중이면_돌려받은_상태와_시각이_없고_쿠폰_정책의_이름과_할인_조건을_싣는다() {
        CouponUsageView view = CouponUsageView.of(reserved(), COUPON);

        assertThat(view.usageId()).isEqualTo(77L);
        assertThat(view.memberCouponId()).isEqualTo(31L);
        assertThat(view.couponId()).isEqualTo(5L);
        assertThat(view.couponName()).isEqualTo(COUPON_NAME);
        assertThat(view.couponType()).isEqualTo(CouponType.SELLER);
        assertThat(view.discountType()).isEqualTo(DiscountType.RATE);
        assertThat(view.discountValue()).isEqualTo(10);
        assertThat(view.lectureId()).isEqualTo(LECTURE_ID);
        assertThat(view.orderId()).isEqualTo(900L);
        assertThat(view.orderItemId()).isEqualTo(901L);
        assertThat(view.itemPrice()).isEqualTo(10_000);
        assertThat(view.discountAmount()).isEqualTo(1_000);
        assertThat(view.status()).isEqualTo(CouponUsageStatus.RESERVED);
        assertThat(view.reservedAt()).isEqualTo(NOW);
        assertThat(view.returnedTo()).isNull();
        assertThat(view.returnedAt()).isNull();
    }

    @Test
    void 결제_완료면_결제_시각만_있고_돌려받은_상태는_없다() {
        CouponUsage usage = reserved();
        usage.applyPaid("order-item-paid:901", PAID_AT, MemberCouponStatus.RESERVED, 1, PAID_AT);

        CouponUsageView view = CouponUsageView.of(usage, COUPON);

        assertThat(view.status()).isEqualTo(CouponUsageStatus.PAID);
        assertThat(view.paidAt()).isEqualTo(PAID_AT);
        assertThat(view.returnedTo()).isNull();
        assertThat(view.returnedAt()).isNull();
    }

    @Test
    void 해제면_해제_결과와_시각을_돌려받은_상태로_싣는다() {
        CouponUsage usage = reserved();
        usage.release("lecture-order:900:release", "결제 실패", null, MemberCouponStatus.EXPIRED, RETURNED_AT);

        CouponUsageView view = CouponUsageView.of(usage, COUPON);

        assertThat(view.status()).isEqualTo(CouponUsageStatus.RELEASED);
        assertThat(view.releasedAt()).isEqualTo(RETURNED_AT);
        assertThat(view.returnedTo()).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(view.returnedAt()).isEqualTo(RETURNED_AT);
        assertThat(view.canceledAt()).isNull();
    }

    @Test
    void 취소_완료면_복원_결과와_시각을_돌려받은_상태로_싣고_취소_완료_시각과_결제_시각도_싣는다() {
        CouponUsage usage = reserved();
        usage.applyPaid("order-item-paid:901", PAID_AT, MemberCouponStatus.RESERVED, 1, PAID_AT);
        usage.applyCancelCompleted("order-item-cancel-completed:901", NOW.plusSeconds(15), MemberCouponStatus.USED, 1,
                NOW.plusSeconds(3_600), RETURNED_AT);

        CouponUsageView view = CouponUsageView.of(usage, COUPON);

        assertThat(view.status()).isEqualTo(CouponUsageStatus.CANCELED);
        assertThat(view.paidAt()).isEqualTo(PAID_AT);
        assertThat(view.canceledAt()).isEqualTo(NOW.plusSeconds(15));
        assertThat(view.returnedTo()).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(view.returnedAt()).isEqualTo(RETURNED_AT);
        assertThat(view.releasedAt()).isNull();
    }
}
