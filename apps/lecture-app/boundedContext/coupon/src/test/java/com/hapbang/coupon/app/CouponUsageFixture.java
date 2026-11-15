package com.hapbang.coupon.app;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.hapbang.coupon.config.CouponCalculateProperties;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponUsageException;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.OwnedCoupon;
import com.hapbang.coupon.out.ProcessedOperationRepository;

/** 쿠폰 사용 서비스 테스트 값. 저장한 것처럼 ID를 붙인 쿠폰·발급 쿠폰과 아무것도 하지 않는 트랜잭션 대역 */
final class CouponUsageFixture {

    static final long ORDER_ID = 900L;
    static final long ORDER_ITEM_ID = 901L;
    static final long PRICE = 10_000L;
    static final Instant EXPIRES_AT = NOW.plusSeconds(86_400);

    private CouponUsageFixture() {
    }

    /** 특정 강의(10,000 HP) 10% 판매자 쿠폰(중첩 가능) */
    static Coupon sellerRateCoupon(long couponId) {
        return withId(Coupon.createSeller(SELLER_ID, lectureRate().stackable(true).build(), NOW), couponId);
    }

    /** 모든 강의 1,500 HP 플랫폼 쿠폰(중첩 가능) */
    static Coupon platformAnyAmountCoupon(long couponId) {
        return withId(Coupon.createPlatform(ADMIN_ID, anyLectureAmount().stackable(true).build(), NOW), couponId);
    }

    static Coupon withId(Coupon coupon, long couponId) {
        ReflectionTestUtils.setField(coupon, "id", couponId);
        return coupon;
    }

    /** 회원의 AVAILABLE 발급 쿠폰(세대 0) */
    static OwnedCoupon owned(long memberCouponId, long memberId, Coupon coupon) {
        MemberCoupon memberCoupon = MemberCoupon.issue(memberId, coupon.getId(), "req-" + memberCouponId, NOW,
                EXPIRES_AT, NOW);
        ReflectionTestUtils.setField(memberCoupon, "id", memberCouponId);
        return new OwnedCoupon(memberCoupon, coupon);
    }

    static CouponUsageSupport support(MemberCouponRepository memberCouponRepository,
                                      ProcessedOperationRepository processedOperationRepository,
                                      PlatformTransactionManager transactionManager) {
        return new CouponUsageSupport(CouponCalculateProperties.defaults(), memberCouponRepository,
                processedOperationRepository, transactionManager);
    }

    /** 실제 트랜잭션 없이 콜백만 실행하는 트랜잭션 관리자 mock. 커밋·롤백 호출은 verify로 본다 */
    static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock();
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        return transactionManager;
    }

    static void givenOwnedByIds(MemberCouponRepository repository, OwnedCoupon... owned) {
        when(repository.findOwnedByIds(any())).thenReturn(List.of(owned));
    }

    static void assertUsageError(ThrowingCallable call, CouponErrorCode code, Long memberCouponId) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(CouponUsageException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(code);
                    assertThat(exception.getMemberCouponId()).isEqualTo(memberCouponId);
                });
    }
}
