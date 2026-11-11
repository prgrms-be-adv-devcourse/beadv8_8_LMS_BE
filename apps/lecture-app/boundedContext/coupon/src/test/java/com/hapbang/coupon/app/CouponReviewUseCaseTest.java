package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponDomainException;
import com.hapbang.coupon.domain.CouponStatus;

// 심사 거절 커밋 뒤 용량 예약을 바로 푸는지와 그 실패가 거절을 되돌리지 않는지
class CouponReviewUseCaseTest {

    private final CouponSupport couponSupport = mock();
    private final CouponCapacitySupport capacitySupport = mock();
    private final PlatformTransactionManager transactionManager = mock();
    private CouponReviewUseCase useCase;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        useCase = new CouponReviewUseCase(couponSupport, capacitySupport, transactionManager, CLOCK);
    }

    @Test
    void 거절을_커밋한_뒤_용량_예약을_바로_푼다() {
        when(couponSupport.findCoupon(COUPON_ID)).thenReturn(reviewSellerCoupon());

        CouponView view = useCase.reject(ADMIN_ID, COUPON_ID, "할인율이 과도합니다");

        assertThat(view.status()).isEqualTo(CouponStatus.REJECTED);
        InOrder order = inOrder(transactionManager, capacitySupport);
        order.verify(transactionManager).commit(any());
        order.verify(capacitySupport).release(COUPON_ID);
    }

    @Test
    void 용량_예약을_풀지_못해도_거절은_성공한다() {
        when(couponSupport.findCoupon(COUPON_ID)).thenReturn(reviewSellerCoupon());
        when(capacitySupport.release(COUPON_ID)).thenThrow(new IllegalStateException("DB 오류"));

        CouponView view = useCase.reject(ADMIN_ID, COUPON_ID, "할인율이 과도합니다");

        assertThat(view.status()).isEqualTo(CouponStatus.REJECTED);
    }

    @Test
    void 거절할_수_없는_상태면_용량_예약을_건드리지_않는다() {
        Coupon open = openSellerCoupon();
        when(couponSupport.findCoupon(COUPON_ID)).thenReturn(open);

        assertThatThrownBy(() -> useCase.reject(ADMIN_ID, COUPON_ID, "사유"))
                .isInstanceOf(CouponDomainException.class);
        verifyNoInteractions(capacitySupport);
    }

    @Test
    void 심사_요청은_용량을_예약하지_않는다() {
        Coupon draft = withId(sellerCoupon());
        when(couponSupport.findCoupon(COUPON_ID)).thenReturn(draft);

        useCase.submitReview(SELLER_ID, COUPON_ID);

        verifyNoInteractions(capacitySupport);
    }
}
