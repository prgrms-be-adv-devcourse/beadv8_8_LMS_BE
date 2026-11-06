package com.hapbang.coupon.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.coupon.domain.CouponIssueRequest;

/** 발급 요청 최종 결과 저장소. 발급 요청 ID는 쿠폰 안에서만 유일하므로 쿠폰 ID와 함께 읽는다 */
public interface CouponIssueRequestRepository extends JpaRepository<CouponIssueRequest, Long> {

    Optional<CouponIssueRequest> findByCouponIdAndIssueRequestId(Long couponId, String issueRequestId);
}
