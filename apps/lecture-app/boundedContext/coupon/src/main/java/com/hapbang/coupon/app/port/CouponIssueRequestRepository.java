package com.hapbang.coupon.app.port;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.coupon.domain.CouponIssueRequest;

/**
 * 발급 요청 최종 결과 저장소. 발급 요청 ID는 쿠폰 안에서만 유일하므로 쿠폰 ID와 함께 읽는다. 재발행·복구·대사의 결과 조회는
 * {@link CouponIssueRequestRepositoryCustom}(QueryDSL)이 맡는다.
 */
public interface CouponIssueRequestRepository extends Repository<CouponIssueRequest, Long>,
        CouponIssueRequestRepositoryCustom {

    // 쓰는 기본 메서드. 구현은 Spring Data JPA 기본 구현(SimpleJpaRepository)이 맡는다
    <S extends CouponIssueRequest> S save(S entity);

    /** 쿠폰 안의 이 발급 요청의 최종 결과 */
    Optional<CouponIssueRequest> findByCouponIdAndIssueRequestId(Long couponId, String issueRequestId);
}
