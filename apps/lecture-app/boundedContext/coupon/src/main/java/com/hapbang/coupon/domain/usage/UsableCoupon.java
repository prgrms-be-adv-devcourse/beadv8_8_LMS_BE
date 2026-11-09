package com.hapbang.coupon.domain.usage;

import java.time.Instant;
import java.util.Objects;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;

/**
 * 계산에 쓰는 발급 쿠폰 한 장과 그 정책 조건. 발급 쿠폰과 쿠폰 정책을 합친 읽기 전용 값이다.
 *
 * @param generation 지금 사용 시도 세대. 예약 CAS의 기대값이 된다
 */
public record UsableCoupon(
        long memberCouponId,
        long memberId,
        long couponId,
        CouponType type,
        CouponTargetType targetType,
        Long lectureId,
        Long basePrice,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        boolean stackable,
        int policyVersion,
        long generation,
        MemberCouponStatus status,
        Instant expiresAt
) {

    public UsableCoupon {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(discountType, "discountType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (targetType == CouponTargetType.LECTURE && (lectureId == null || basePrice == null)) {
            throw new IllegalArgumentException("특정 강의 대상 쿠폰은 강의와 강의 가격이 필요합니다: " + memberCouponId);
        }
    }

    /** 발급 쿠폰과 그 쿠폰 정책으로 만든다. 두 값의 쿠폰 ID가 다르면 거절한다 */
    public static UsableCoupon of(MemberCoupon memberCoupon, Coupon coupon) {
        Objects.requireNonNull(memberCoupon, "memberCoupon");
        Objects.requireNonNull(coupon, "coupon");
        if (!Objects.equals(memberCoupon.getCouponId(), coupon.getId())) {
            throw new IllegalArgumentException("발급 쿠폰의 쿠폰 정책이 아닙니다: memberCouponId=%d, couponId=%d"
                    .formatted(memberCoupon.getId(), coupon.getId()));
        }
        return new UsableCoupon(memberCoupon.getId(), memberCoupon.getMemberId(), coupon.getId(), coupon.getType(),
                coupon.getTargetType(), coupon.getLectureId(), coupon.getBasePrice(), coupon.getDiscountType(),
                coupon.getDiscountValue(), coupon.getMaxDiscountAmount(), coupon.isStackable(),
                coupon.getPolicyVersion(), memberCoupon.getGeneration(), memberCoupon.getStatus(),
                memberCoupon.getExpiresAt());
    }

    /** 이 회원이 지금 쓸 수 있는가: 본인 소유, AVAILABLE, 만료 전(expiresAt > now) */
    public boolean isUsableBy(long memberId, Instant now) {
        return this.memberId == memberId && status == MemberCouponStatus.AVAILABLE && expiresAt.isAfter(now);
    }

    /** 이 강의에 적용할 수 있는가. 특정 강의 대상은 강의가 같을 때, 모든 강의 대상은 모든 강의다 */
    public boolean appliesTo(long lectureId) {
        return targetType == CouponTargetType.ANY_LECTURE || this.lectureId == lectureId;
    }
}
