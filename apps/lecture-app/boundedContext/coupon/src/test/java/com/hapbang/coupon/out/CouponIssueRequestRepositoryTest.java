package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class CouponIssueRequestRepositoryTest {

    @Autowired
    private CouponIssueRequestRepository couponIssueRequestRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 쿠폰과_요청_ID로_최종_결과를_읽고_같은_쿠폰의_같은_요청_ID는_다시_저장하지_않는다() {
        Long couponId = saveCoupon();
        MemberCoupon issued = memberCouponRepository.save(
                MemberCoupon.issue(MEMBER_ID, couponId, "req-1", NOW, NOW.plusSeconds(60), NOW));
        couponIssueRequestRepository.save(
                CouponIssueRequest.issued("req-1", MEMBER_ID, couponId, 1L, NOW, issued.getId(), NOW.plusSeconds(1)));
        flushAndClear();

        CouponIssueRequest found = couponIssueRequestRepository.findByCouponIdAndIssueRequestId(couponId, "req-1")
                .orElseThrow();

        assertThat(found.getResult()).isEqualTo(CouponIssueResult.ISSUED);
        assertThat(found.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(found.getCouponId()).isEqualTo(couponId);
        assertThat(found.getMemberCouponId()).isEqualTo(issued.getId());
        assertThat(found.getAcceptedAt()).isEqualTo(NOW);
        assertThat(couponIssueRequestRepository.findByCouponIdAndIssueRequestId(couponId, "req-unknown")).isEmpty();
        // 같은 쿠폰·같은 요청 ID의 다른 결과는 기존 결과를 덮지 않고 유일 제약 위반이다
        assertThatThrownBy(() -> couponIssueRequestRepository.saveAndFlush(
                CouponIssueRequest.rejected("req-1", MEMBER_ID, couponId, 1L, NOW, "매진", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_coupon_issue_request_coupon_request");
    }

    @Test
    void 다른_쿠폰의_같은_요청_ID는_따로_저장되고_따로_읽힌다() {
        Long couponA = saveCoupon();
        Long couponB = saveCoupon();
        couponIssueRequestRepository.save(
                CouponIssueRequest.rejected("req-1", MEMBER_ID, couponA, 1L, NOW, "매진", NOW));
        couponIssueRequestRepository.save(
                CouponIssueRequest.rejected("req-1", OTHER_MEMBER_ID, couponB, 1L, NOW, "기간 종료", NOW));
        flushAndClear();

        assertThat(couponIssueRequestRepository.findByCouponIdAndIssueRequestId(couponA, "req-1").orElseThrow()
                .getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(couponIssueRequestRepository.findByCouponIdAndIssueRequestId(couponB, "req-1").orElseThrow()
                .getMemberId()).isEqualTo(OTHER_MEMBER_ID);
    }

    private Long saveCoupon() {
        return couponRepository.save(Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW)).getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
