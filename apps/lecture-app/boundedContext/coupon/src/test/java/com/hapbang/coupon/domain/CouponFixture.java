package com.hapbang.coupon.domain;

import java.time.Instant;

/** 쿠폰 도메인 테스트 값. 발행 조건은 {@link #lectureRate()} 등에서 시작해 {@link TermsBuilder}로 한 값씩 바꾼다 */
public final class CouponFixture {

    public static final long SELLER_ID = 10L;
    public static final long ADMIN_ID = 1L;
    public static final long MEMBER_ID = 100L;
    public static final long OTHER_MEMBER_ID = 200L;
    public static final long LECTURE_ID = 7L;
    public static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    public static final Instant ISSUE_START = NOW.plusSeconds(3_600);
    public static final Instant ISSUE_END = NOW.plusSeconds(86_400 * 7);
    public static final String COUPON_NAME = "겨울 특강 할인";

    private CouponFixture() {
    }

    /** 특정 강의(10,000 HP) 10% 100장, 접수 후 30일 만료 */
    public static TermsBuilder lectureRate() {
        return new TermsBuilder()
                .targetType(CouponTargetType.LECTURE).lectureId(LECTURE_ID).basePrice(10_000L)
                .discountType(DiscountType.RATE).discountValue(10);
    }

    /** 특정 강의(10,000 HP) 2,000 HP 100장 */
    public static TermsBuilder lectureAmount() {
        return lectureRate().discountType(DiscountType.AMOUNT).discountValue(2_000);
    }

    /** 모든 강의 10%, 장당 최대 3,000 HP, 100장 */
    public static TermsBuilder anyLectureRate() {
        return new TermsBuilder()
                .targetType(CouponTargetType.ANY_LECTURE).lectureId(null).basePrice(null)
                .discountType(DiscountType.RATE).discountValue(10).maxDiscountAmount(3_000L);
    }

    /** 모든 강의 1,500 HP, 100장 */
    public static TermsBuilder anyLectureAmount() {
        return anyLectureRate().discountType(DiscountType.AMOUNT).discountValue(1_500).maxDiscountAmount(null);
    }

    public static Coupon sellerCoupon() {
        return Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW);
    }

    public static Coupon platformCoupon() {
        return Coupon.createPlatform(ADMIN_ID, lectureRate().build(), NOW);
    }

    public static final class TermsBuilder {

        private String name = COUPON_NAME;
        private CouponTargetType targetType;
        private Long lectureId;
        private Long basePrice;
        private DiscountType discountType;
        private long discountValue;
        private Long maxDiscountAmount;
        private int quantity = 100;
        private Instant issueStartAt = ISSUE_START;
        private Instant issueEndAt = ISSUE_END;
        private ExpiryType expiryType = ExpiryType.RELATIVE;
        private Integer expiryDays = 30;
        private Instant fixedExpiresAt;
        private boolean stackable;

        public TermsBuilder name(String value) {
            this.name = value;
            return this;
        }

        public TermsBuilder targetType(CouponTargetType value) {
            this.targetType = value;
            return this;
        }

        public TermsBuilder lectureId(Long value) {
            this.lectureId = value;
            return this;
        }

        public TermsBuilder basePrice(Long value) {
            this.basePrice = value;
            return this;
        }

        public TermsBuilder discountType(DiscountType value) {
            this.discountType = value;
            return this;
        }

        public TermsBuilder discountValue(long value) {
            this.discountValue = value;
            return this;
        }

        public TermsBuilder maxDiscountAmount(Long value) {
            this.maxDiscountAmount = value;
            return this;
        }

        public TermsBuilder quantity(int value) {
            this.quantity = value;
            return this;
        }

        public TermsBuilder issuePeriod(Instant start, Instant end) {
            this.issueStartAt = start;
            this.issueEndAt = end;
            return this;
        }

        public TermsBuilder relative(Integer days) {
            this.expiryType = ExpiryType.RELATIVE;
            this.expiryDays = days;
            this.fixedExpiresAt = null;
            return this;
        }

        public TermsBuilder fixed(Instant expiresAt) {
            this.expiryType = ExpiryType.FIXED;
            this.expiryDays = null;
            this.fixedExpiresAt = expiresAt;
            return this;
        }

        public TermsBuilder expiry(ExpiryType type, Integer days, Instant expiresAt) {
            this.expiryType = type;
            this.expiryDays = days;
            this.fixedExpiresAt = expiresAt;
            return this;
        }

        public TermsBuilder stackable(boolean value) {
            this.stackable = value;
            return this;
        }

        public CouponTerms build() {
            return new CouponTerms(name, targetType, lectureId, basePrice, discountType, discountValue, maxDiscountAmount,
                    quantity, issueStartAt, issueEndAt, expiryType, expiryDays, fixedExpiresAt, stackable);
        }
    }
}
