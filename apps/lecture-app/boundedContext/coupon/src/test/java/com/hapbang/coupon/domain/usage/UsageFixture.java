package com.hapbang.coupon.domain.usage;

import java.time.Instant;

import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.MemberCouponStatus;

/** 쿠폰 사용 계산 테스트 값. 쿠폰은 {@link #seller} 등에서 시작해 {@link CouponBuilder}로 한 값씩 바꾼다 */
final class UsageFixture {

    static final long MEMBER_ID = 100L;
    static final long OTHER_MEMBER_ID = 200L;
    static final long SELLER_ID = 10L;
    static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    static final Instant EXPIRES_AT = NOW.plusSeconds(86_400 * 30);
    static final int MAX_NODES = 100_000;

    private UsageFixture() {
    }

    /** 계산용 항목(주문 항목 ID 없음) */
    static UsageItem item(long lectureId, long price) {
        return new UsageItem(null, lectureId, SELLER_ID, price);
    }

    /** 판매자 쿠폰: 그 강의(10,000 HP) 10%, 중첩 가능 */
    static CouponBuilder seller(long memberCouponId, long lectureId) {
        return new CouponBuilder(memberCouponId, CouponType.SELLER, CouponTargetType.LECTURE)
                .lectureId(lectureId).basePrice(10_000L);
    }

    /** 특정 강의 대상 플랫폼 쿠폰: 그 강의(10,000 HP) 10%, 중첩 가능 */
    static CouponBuilder platformLecture(long memberCouponId, long lectureId) {
        return new CouponBuilder(memberCouponId, CouponType.PLATFORM, CouponTargetType.LECTURE)
                .lectureId(lectureId).basePrice(10_000L);
    }

    /** 모든 강의 대상 플랫폼 쿠폰: 10%, 장당 최대 1,000,000 HP(사실상 상한 없음), 중첩 가능 */
    static CouponBuilder platformAny(long memberCouponId) {
        return new CouponBuilder(memberCouponId, CouponType.PLATFORM, CouponTargetType.ANY_LECTURE)
                .maxDiscount(1_000_000L);
    }

    static final class CouponBuilder {

        private final long memberCouponId;
        private final CouponType type;
        private final CouponTargetType targetType;
        private long memberId = MEMBER_ID;
        private Long lectureId;
        private Long basePrice;
        private DiscountType discountType = DiscountType.RATE;
        private long discountValue = 10;
        private Long maxDiscountAmount;
        private boolean stackable = true;
        private int policyVersion = 1;
        private long generation;
        private MemberCouponStatus status = MemberCouponStatus.AVAILABLE;
        private Instant expiresAt = EXPIRES_AT;

        private CouponBuilder(long memberCouponId, CouponType type, CouponTargetType targetType) {
            this.memberCouponId = memberCouponId;
            this.type = type;
            this.targetType = targetType;
        }

        CouponBuilder lectureId(Long value) {
            this.lectureId = value;
            return this;
        }

        CouponBuilder basePrice(Long value) {
            this.basePrice = value;
            return this;
        }

        CouponBuilder rate(long percent) {
            this.discountType = DiscountType.RATE;
            this.discountValue = percent;
            return this;
        }

        CouponBuilder amount(long hp) {
            this.discountType = DiscountType.AMOUNT;
            this.discountValue = hp;
            this.maxDiscountAmount = null;
            return this;
        }

        CouponBuilder maxDiscount(Long value) {
            this.maxDiscountAmount = value;
            return this;
        }

        CouponBuilder stackable(boolean value) {
            this.stackable = value;
            return this;
        }

        CouponBuilder owner(long value) {
            this.memberId = value;
            return this;
        }

        CouponBuilder policyVersion(int value) {
            this.policyVersion = value;
            return this;
        }

        CouponBuilder generation(long value) {
            this.generation = value;
            return this;
        }

        CouponBuilder status(MemberCouponStatus value) {
            this.status = value;
            return this;
        }

        CouponBuilder expiresAt(Instant value) {
            this.expiresAt = value;
            return this;
        }

        UsableCoupon build() {
            // 쿠폰 정책 ID는 발급 쿠폰 ID에 1000을 더한 값으로 둔다(테스트에서 구분만 되면 된다)
            return new UsableCoupon(memberCouponId, memberId, memberCouponId + 1_000, type, targetType, lectureId,
                    basePrice, discountType, discountValue, maxDiscountAmount, stackable, policyVersion, generation,
                    status, expiresAt);
        }
    }
}
