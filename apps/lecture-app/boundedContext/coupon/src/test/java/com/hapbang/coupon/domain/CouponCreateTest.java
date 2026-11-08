package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CouponCreateTest {

    private static final int MAX_EXPIRY_DAYS = 30;

    @Test
    void 판매자_쿠폰은_신청자와_생성자가_판매자이고_DRAFT로_시작한다() {
        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().stackable(true).build(), NOW);

        assertThat(coupon.getType()).isEqualTo(CouponType.SELLER);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.DRAFT);
        assertThat(coupon.getApplicantId()).isEqualTo(SELLER_ID);
        assertThat(coupon.getCreatedBy()).isEqualTo(SELLER_ID);
        assertThat(coupon.getIssuedCount()).isZero();
        assertThat(coupon.getPolicyVersion()).isEqualTo(Coupon.INITIAL_POLICY_VERSION);
        assertThat(coupon.getEpoch()).isEqualTo(Coupon.INITIAL_EPOCH);
        assertThat(coupon.isRedisReady()).isFalse();
        assertThat(coupon.isStackable()).isTrue();
        assertThat(coupon.terms()).isEqualTo(lectureRate().stackable(true).build());
        assertThat(coupon.isOwnedBy(SELLER_ID)).isTrue();
        assertThat(coupon.isOwnedBy(OTHER_MEMBER_ID)).isFalse();
    }

    @Test
    void 플랫폼_쿠폰은_모든_강의_대상으로도_발행한다() {
        Coupon coupon = Coupon.createPlatform(ADMIN_ID, anyLectureRate().build(), NOW);

        assertThat(coupon.getType()).isEqualTo(CouponType.PLATFORM);
        assertThat(coupon.getApplicantId()).isEqualTo(ADMIN_ID);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.DRAFT);
    }

    @Test
    void 판매자_쿠폰은_모든_강의_대상으로_발행할_수_없다() {
        assertError(() -> Coupon.createSeller(SELLER_ID, anyLectureAmount().build(), NOW),
                CouponDomainError.SELLER_TARGET_LECTURE_ONLY);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 수량은_1장_이상이어야_한다(int quantity) {
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().quantity(quantity).build(), NOW),
                CouponDomainError.INVALID_QUANTITY);
    }

    @Test
    void 수량_1장은_발행한다() {
        assertThat(Coupon.createSeller(SELLER_ID, lectureRate().quantity(1).build(), NOW).getQuantity()).isEqualTo(1);
    }

    @Test
    void 발급_시작이_종료와_같거나_늦으면_거절한다() {
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().issuePeriod(ISSUE_END, ISSUE_END).build(), NOW),
                CouponDomainError.INVALID_ISSUE_PERIOD);
        assertError(() -> Coupon.createSeller(SELLER_ID,
                        lectureRate().issuePeriod(ISSUE_END, ISSUE_START).build(), NOW),
                CouponDomainError.INVALID_ISSUE_PERIOD);
    }

    @Test
    void 발급_시작이_종료보다_1밀리초라도_앞서면_발행하고_밀리초_아래_차이는_같은_시각으로_본다() {
        CouponTerms terms = lectureRate().issuePeriod(ISSUE_END.minusMillis(1), ISSUE_END).build();

        assertThatCode(() -> Coupon.createSeller(SELLER_ID, terms, NOW)).doesNotThrowAnyException();
        // Redis·Kafka 이벤트는 밀리초까지라 1마이크로초 차이는 같은 시각이 된다
        assertError(() -> Coupon.createSeller(SELLER_ID,
                        lectureRate().issuePeriod(ISSUE_END.plusNanos(1_000), ISSUE_END.plusNanos(999_999)).build(), NOW),
                CouponDomainError.INVALID_ISSUE_PERIOD);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 101})
    void 정률은_1부터_100까지만_허용한다(long rate) {
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().discountValue(rate).build(), NOW),
                CouponDomainError.INVALID_RATE);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 100})
    void 정률_경계값_1과_100은_발행한다(long rate) {
        assertThatCode(() -> Coupon.createSeller(SELLER_ID, lectureRate().discountValue(rate).build(), NOW))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 정액은_1HP_이상이어야_한다(long amount) {
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureAmount().discountValue(amount).build(), NOW),
                CouponDomainError.INVALID_AMOUNT);
    }

    @Test
    void 특정_강의_정액은_강의_가격보다_커도_발행한다() {
        CouponTerms terms = lectureAmount().basePrice(1_000L).discountValue(5_000).build();

        assertThatCode(() -> Coupon.createSeller(SELLER_ID, terms, NOW)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 모든_강의_정률은_최대_할인액이_1HP_이상_필요하다(long maxDiscount) {
        assertError(() -> Coupon.createPlatform(ADMIN_ID, anyLectureRate().maxDiscountAmount(maxDiscount).build(), NOW),
                CouponDomainError.MAX_DISCOUNT_REQUIRED);
        assertError(() -> Coupon.createPlatform(ADMIN_ID, anyLectureRate().maxDiscountAmount(null).build(), NOW),
                CouponDomainError.MAX_DISCOUNT_REQUIRED);
    }

    @Test
    void 모든_강의_정률이_아니면_최대_할인액을_받지_않는다() {
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().maxDiscountAmount(1_000L).build(), NOW),
                CouponDomainError.MAX_DISCOUNT_NOT_ALLOWED);
        assertError(() -> Coupon.createPlatform(ADMIN_ID, lectureAmount().maxDiscountAmount(1_000L).build(), NOW),
                CouponDomainError.MAX_DISCOUNT_NOT_ALLOWED);
        assertError(() -> Coupon.createPlatform(ADMIN_ID, anyLectureAmount().maxDiscountAmount(1_000L).build(), NOW),
                CouponDomainError.MAX_DISCOUNT_NOT_ALLOWED);
    }

    @Test
    void 특정_강의_대상은_강의와_가격이_필요하다() {
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().lectureId(null).build(), NOW),
                CouponDomainError.LECTURE_TARGET_REQUIRED);
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().basePrice(null).build(), NOW),
                CouponDomainError.LECTURE_TARGET_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 강의_가격_snapshot은_1HP_이상이어야_한다(long price) {
        // 무료 강의는 등록하지 않는다
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().basePrice(price).build(), NOW),
                CouponDomainError.INVALID_BASE_PRICE);
    }

    @Test
    void 강의_가격_snapshot_1HP는_발행한다() {
        assertThatCode(() -> Coupon.createSeller(SELLER_ID, lectureRate().basePrice(1L).build(), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void 모든_강의_대상에는_강의와_가격을_지정하지_않는다() {
        assertError(() -> Coupon.createPlatform(ADMIN_ID, anyLectureAmount().lectureId(LECTURE_ID).build(), NOW),
                CouponDomainError.LECTURE_TARGET_NOT_ALLOWED);
        assertError(() -> Coupon.createPlatform(ADMIN_ID, anyLectureAmount().basePrice(10_000L).build(), NOW),
                CouponDomainError.LECTURE_TARGET_NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 접수_기준_만료는_1일_이상이어야_한다(int days) {
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().relative(days).build(), NOW),
                CouponDomainError.INVALID_RELATIVE_EXPIRY);
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().relative(null).build(), NOW),
                CouponDomainError.INVALID_RELATIVE_EXPIRY);
    }

    @Test
    void 접수_기준_만료에_고정_시각을_함께_주면_거절한다() {
        CouponTerms terms = lectureRate().expiry(ExpiryType.RELATIVE, 30, ISSUE_END).build();

        assertError(() -> Coupon.createSeller(SELLER_ID, terms, NOW), CouponDomainError.INVALID_RELATIVE_EXPIRY);
    }

    @Test
    void 고정_만료는_만료_시각이_필요하고_일수를_함께_받지_않는다() {
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().fixed(null).build(), NOW),
                CouponDomainError.INVALID_FIXED_EXPIRY);
        assertError(() -> Coupon.createSeller(SELLER_ID,
                        lectureRate().expiry(ExpiryType.FIXED, 30, ISSUE_END).build(), NOW),
                CouponDomainError.INVALID_FIXED_EXPIRY);
    }

    @Test
    void 고정_만료_시각이_발급_종료와_같거나_앞서면_거절한다() {
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().fixed(ISSUE_END).build(), NOW),
                CouponDomainError.INVALID_FIXED_EXPIRY);
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().fixed(ISSUE_END.minusNanos(1)).build(), NOW),
                CouponDomainError.INVALID_FIXED_EXPIRY);
    }

    @Test
    void 고정_만료_시각이_발급_종료보다_1밀리초라도_뒤면_발행하고_밀리초_아래_차이는_같은_시각으로_본다() {
        CouponTerms terms = lectureRate().fixed(ISSUE_END.plusMillis(1)).build();

        assertThatCode(() -> Coupon.createSeller(SELLER_ID, terms, NOW)).doesNotThrowAnyException();
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().fixed(ISSUE_END.plusNanos(999_999)).build(), NOW),
                CouponDomainError.INVALID_FIXED_EXPIRY);
    }

    @Test
    void 필수_값이_빠지면_거절한다() {
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().discountType(null).build(), NOW),
                CouponDomainError.MISSING_TERMS);
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().issuePeriod(null, ISSUE_END).build(), NOW),
                CouponDomainError.MISSING_TERMS);
    }

    @Test
    void 고정_만료_쿠폰을_발행한다() {
        Instant fixed = ISSUE_END.plusSeconds(86_400);

        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().fixed(fixed).build(), NOW);

        assertThat(coupon.getExpiryType()).isEqualTo(ExpiryType.FIXED);
        assertThat(coupon.getFixedExpiresAt()).isEqualTo(fixed);
        assertThat(coupon.getExpiryDays()).isNull();
    }

    @Test
    void 발급_종료_시각이_지금과_같거나_지났으면_발행하지_않는다() {
        CouponTerms endsNow = lectureRate().issuePeriod(NOW.minusSeconds(3_600), NOW).build();
        CouponTerms ended = lectureRate().issuePeriod(NOW.minusSeconds(3_600), NOW.minusMillis(1)).build();

        assertError(() -> Coupon.createSeller(SELLER_ID, endsNow, NOW), CouponDomainError.ISSUE_PERIOD_ENDED);
        assertError(() -> Coupon.createSeller(SELLER_ID, ended, NOW), CouponDomainError.ISSUE_PERIOD_ENDED);
        assertError(() -> Coupon.createPlatform(ADMIN_ID, endsNow, NOW), CouponDomainError.ISSUE_PERIOD_ENDED);
    }

    @Test
    void 발급_종료가_지금보다_1밀리초라도_뒤면_발행하고_밀리초_아래로_뒤면_지금과_같게_본다() {
        CouponTerms terms = lectureRate().issuePeriod(NOW.minusSeconds(3_600), NOW.plusMillis(1)).build();
        CouponTerms underMilli = lectureRate().issuePeriod(NOW.minusSeconds(3_600), NOW.plusNanos(999_000)).build();

        assertThatCode(() -> Coupon.createSeller(SELLER_ID, terms, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> Coupon.createPlatform(ADMIN_ID, terms, NOW)).doesNotThrowAnyException();
        assertError(() -> Coupon.createSeller(SELLER_ID, underMilli, NOW), CouponDomainError.ISSUE_PERIOD_ENDED);
    }

    @Test
    void 접수_기준_만료는_상한_일수까지_발행하고_넘으면_거절한다() {
        assertThatCode(() -> Coupon.createSeller(SELLER_ID, lectureRate().relative(MAX_EXPIRY_DAYS).build(),
                MAX_EXPIRY_DAYS, NOW)).doesNotThrowAnyException();
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().relative(MAX_EXPIRY_DAYS + 1).build(),
                MAX_EXPIRY_DAYS, NOW), CouponDomainError.EXPIRY_TOO_LONG);
        assertError(() -> Coupon.createPlatform(ADMIN_ID, anyLectureAmount().relative(MAX_EXPIRY_DAYS + 1).build(),
                MAX_EXPIRY_DAYS, NOW), CouponDomainError.EXPIRY_TOO_LONG);
    }

    @Test
    void 고정_만료는_발급_종료_뒤_상한_일수까지_발행하고_1밀리초라도_넘으면_거절한다() {
        Instant limit = ISSUE_END.plus(Duration.ofDays(MAX_EXPIRY_DAYS));

        assertThatCode(() -> Coupon.createSeller(SELLER_ID, lectureRate().fixed(limit).build(), MAX_EXPIRY_DAYS, NOW))
                .doesNotThrowAnyException();
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().fixed(limit.plusMillis(1)).build(),
                MAX_EXPIRY_DAYS, NOW), CouponDomainError.EXPIRY_TOO_LONG);
    }

    @Test
    void 상한을_넘기지_않는_팩토리는_DB_한도_3650일을_상한으로_쓴다() {
        assertThatCode(() -> Coupon.createSeller(SELLER_ID, lectureRate().relative(3_650).build(), NOW))
                .doesNotThrowAnyException();
        assertError(() -> Coupon.createSeller(SELLER_ID, lectureRate().relative(3_651).build(), NOW),
                CouponDomainError.EXPIRY_TOO_LONG);
    }

    static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, CouponDomainError error) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(CouponDomainException.class, e -> assertThat(e.getError()).isEqualTo(error));
    }
}
