package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.RecoveryIssueResult;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class RecoveryLookupRepositoryTest {

    @Autowired
    private RecoveryLookupRepository recoveryLookupRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponIssueRequestRepository couponIssueRequestRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 쿠폰의_확정_결과와_DB에_있는_요청_ID를_그_쿠폰_안에서만_찾는다() {
        Long couponId = openCoupon().getId();
        Long otherCouponId = openCoupon().getId();
        MemberCoupon issued = memberCouponRepository.save(
                MemberCoupon.issue(MEMBER_ID, couponId, "req-a", NOW, NOW.plusSeconds(60), NOW));
        couponIssueRequestRepository.save(
                CouponIssueRequest.issued("req-a", MEMBER_ID, couponId, 1L, NOW, issued.getId(), NOW));
        couponIssueRequestRepository.save(
                CouponIssueRequest.rejected("req-b", OTHER_MEMBER_ID, couponId, 2L, NOW, "매진", NOW));
        couponIssueRequestRepository.save(
                CouponIssueRequest.rejected("req-x", OTHER_MEMBER_ID, otherCouponId, 1L, NOW, "매진", NOW));
        entityManager.flush();
        entityManager.clear();

        assertThat(recoveryLookupRepository.findResults(couponId)).containsExactly(
                new RecoveryIssueResult("req-a", MEMBER_ID, CouponIssueResult.ISSUED, NOW, 1L),
                new RecoveryIssueResult("req-b", OTHER_MEMBER_ID, CouponIssueResult.REJECTED, NOW, 2L));
        assertThat(recoveryLookupRepository.findExistingRequestIds(couponId, List.of("req-a", "req-x", "req-none")))
                .containsExactly("req-a");
    }

    @Test
    void 프로세스_점검_대상은_Redis_준비를_마친_발급_중_쿠폰이고_보관_기간이_지난_쿠폰은_빠진다() {
        Coupon open = openCoupon();
        couponRepository.save(Coupon.createPlatform(ADMIN_ID, anyLectureAmount().build(), NOW));
        entityManager.flush();

        List<CouponStatus> statuses = List.of(CouponStatus.OPEN, CouponStatus.SUSPENDED);
        assertThat(recoveryLookupRepository.findRedisReadyCouponIds(statuses, NOW)).containsExactly(open.getId());
        assertThat(recoveryLookupRepository.findRedisReadyCouponIds(statuses, ISSUE_END)).isEmpty();
    }

    private Coupon openCoupon() {
        Coupon coupon = Coupon.createSeller(SELLER_ID, lectureRate().build(), NOW);
        coupon.submitReview(NOW);
        coupon.markRedisReady(NOW);
        coupon.open(NOW);
        return couponRepository.save(coupon);
    }
}
