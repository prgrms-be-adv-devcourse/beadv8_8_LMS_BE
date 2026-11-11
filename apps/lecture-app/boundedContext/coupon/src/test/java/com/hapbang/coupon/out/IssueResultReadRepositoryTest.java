package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class IssueResultReadRepositoryTest {

    @Autowired
    private IssueResultReadRepository resultRepository;

    @Autowired
    private CouponIssueRequestRepository requestRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Test
    void 쿠폰의_발급_요청_결과만_요청_ID_회원_결과_epoch로_읽는다() {
        long couponId = couponRepository.saveAndFlush(sellerCoupon()).getId();
        long otherCouponId = couponRepository.saveAndFlush(sellerCoupon()).getId();
        requestRepository.save(CouponIssueRequest.issued("r1", MEMBER_ID, couponId, 1, NOW, 77L, NOW));
        requestRepository.save(CouponIssueRequest.rejected("r2", OTHER_MEMBER_ID, couponId, 2, NOW, "총량 초과", NOW));
        requestRepository.saveAndFlush(CouponIssueRequest.issued("r1", MEMBER_ID, otherCouponId, 1, NOW, 78L, NOW));

        assertThat(resultRepository.findRowsByCouponId(couponId)).containsExactlyInAnyOrder(
                new IssueResultRow("r1", MEMBER_ID, CouponIssueResult.ISSUED, 1),
                new IssueResultRow("r2", OTHER_MEMBER_ID, CouponIssueResult.REJECTED, 2));
    }
}
