package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.app.CouponOpenUseCaseTest.assertCouponError;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.out.CouponRepository;

// 판매자·관리자 조회의 본인 확인과 목록 조건(상태·상한) 전달을 본다. 정렬·필터 SQL은 Repository가 맡는다
class CouponPolicyQueryTest {

    private final CouponRepository couponRepository = mock();
    private final CouponPolicyQuery query = new CouponPolicyQuery(couponRepository,
            new CouponSupport(couponRepository, mock(LectureCatalog.class), mock(CouponIssueStore.class)));

    @Test
    void 판매자_목록은_본인_판매자_쿠폰을_상태와_상한으로_조회한다() {
        when(couponRepository.findSellerCoupons(SELLER_ID, CouponStatus.REVIEW, CouponSupport.LIST_LIMIT))
                .thenReturn(List.of(reviewSellerCoupon()));

        assertThat(query.sellerCoupons(SELLER_ID, CouponStatus.REVIEW))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.couponId()).isEqualTo(COUPON_ID);
                    assertThat(view.name()).isEqualTo(COUPON_NAME);
                });
    }

    @Test
    void 판매자_상세는_본인_쿠폰이면_돌려주고_아니면_403이다() {
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(reviewSellerCoupon()));

        assertThat(query.sellerCoupon(SELLER_ID, COUPON_ID).applicantId()).isEqualTo(SELLER_ID);
        assertCouponError(() -> query.sellerCoupon(OTHER_MEMBER_ID, COUPON_ID), CouponErrorCode.NOT_COUPON_OWNER);
    }

    @Test
    void 판매자_상세에서_없는_쿠폰은_404다() {
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        assertCouponError(() -> query.sellerCoupon(SELLER_ID, COUPON_ID), CouponErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    void 관리자_목록은_상태가_없으면_전체를_상한으로_조회한다() {
        when(couponRepository.findAllCoupons(null, CouponSupport.LIST_LIMIT))
                .thenReturn(List.of(draftPlatformCoupon()));

        assertThat(query.allCoupons(null)).hasSize(1);
        verify(couponRepository).findAllCoupons(null, CouponSupport.LIST_LIMIT);
    }

    @Test
    void 관리자_상세는_신청자와_관계없이_보고_없으면_404다() {
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(reviewSellerCoupon()));
        assertThat(query.coupon(COUPON_ID).status()).isEqualTo(CouponStatus.REVIEW);

        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());
        assertCouponError(() -> query.coupon(COUPON_ID), CouponErrorCode.COUPON_NOT_FOUND);
    }
}
