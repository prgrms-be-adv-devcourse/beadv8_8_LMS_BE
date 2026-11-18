package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.coupon.app.port.CouponIssueRequestRepository;
import com.hapbang.coupon.app.port.CouponRepository;
import com.hapbang.coupon.app.port.IssueResultRow;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponFixture;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.RecoveryIssueResult;
import com.hapbang.coupon.support.CouponIssueRequestTestRepository;
import com.hapbang.coupon.support.MemberCouponTestRepository;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class CouponIssueRequestRepositoryTest {

    @Autowired
    private CouponIssueRequestRepository couponIssueRequestRepository;

    @Autowired
    private CouponIssueRequestTestRepository couponIssueRequestTestRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponTestRepository memberCouponTestRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 쿠폰과_요청_ID로_최종_결과를_읽고_같은_쿠폰의_같은_요청_ID는_다시_저장하지_않는다() {
        Long couponId = saveCoupon();
        MemberCoupon issued = memberCouponTestRepository.save(
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
        assertThatThrownBy(() -> couponIssueRequestTestRepository.saveAndFlush(
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

    @Test
    void 쿠폰의_확정_결과와_DB에_있는_요청_ID를_그_쿠폰_안에서만_찾는다() {
        Long couponId = saveCoupon();
        Long otherCouponId = saveCoupon();
        MemberCoupon issued = memberCouponTestRepository.save(
                MemberCoupon.issue(MEMBER_ID, couponId, "req-a", NOW, NOW.plusSeconds(60), NOW));
        couponIssueRequestRepository.save(
                CouponIssueRequest.issued("req-a", MEMBER_ID, couponId, 1L, NOW, issued.getId(), NOW));
        couponIssueRequestRepository.save(
                CouponIssueRequest.rejected("req-b", OTHER_MEMBER_ID, couponId, 2L, NOW, "매진", NOW));
        couponIssueRequestRepository.save(
                CouponIssueRequest.rejected("req-x", OTHER_MEMBER_ID, otherCouponId, 1L, NOW, "매진", NOW));
        // 요청 ID는 쿠폰 안에서만 유일하다: 다른 쿠폰의 같은 요청 ID는 이 쿠폰의 결과에 섞이지 않는다
        couponIssueRequestRepository.save(
                CouponIssueRequest.rejected("req-a", OTHER_MEMBER_ID, otherCouponId, 3L, NOW, "기간 종료", NOW));
        flushAndClear();

        assertThat(couponIssueRequestRepository.findResults(couponId)).containsExactly(
                new RecoveryIssueResult("req-a", MEMBER_ID, CouponIssueResult.ISSUED, NOW, 1L),
                new RecoveryIssueResult("req-b", OTHER_MEMBER_ID, CouponIssueResult.REJECTED, NOW, 2L));
        assertThat(couponIssueRequestRepository.findResultRows(couponId)).containsExactlyInAnyOrder(
                new IssueResultRow("req-a", MEMBER_ID, CouponIssueResult.ISSUED, 1L),
                new IssueResultRow("req-b", OTHER_MEMBER_ID, CouponIssueResult.REJECTED, 2L));
        assertThat(couponIssueRequestRepository.findExistingRequestIds(couponId,
                List.of("req-a", "req-x", "req-none"))).containsExactly("req-a");
        assertThat(couponIssueRequestRepository.findResultRows(otherCouponId)).containsExactlyInAnyOrder(
                new IssueResultRow("req-x", OTHER_MEMBER_ID, CouponIssueResult.REJECTED, 1L),
                new IssueResultRow("req-a", OTHER_MEMBER_ID, CouponIssueResult.REJECTED, 3L));
        assertThat(couponIssueRequestRepository.findExistingRequestIds(otherCouponId, List.of("req-a", "req-b")))
                .containsExactly("req-a");
    }

    private Long saveCoupon() {
        return couponRepository.save(CouponFixture.createSeller(SELLER_ID, lectureRate().build(), NOW)).getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
