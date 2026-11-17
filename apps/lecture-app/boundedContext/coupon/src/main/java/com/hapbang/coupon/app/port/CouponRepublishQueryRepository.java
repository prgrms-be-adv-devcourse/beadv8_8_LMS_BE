package com.hapbang.coupon.app.port;

import org.springframework.data.repository.Repository;

import com.hapbang.coupon.domain.CouponIssueRequest;

/**
 * 재발행 배치의 읽기(읽기 전용). 쓰기와 단건 조회는 {@link CouponRepository}·{@link CouponIssueRequestRepository}가 맡는다.
 * 조회는 {@link CouponRepublishQueryRepositoryCustom}(QueryDSL)이 맡는다.
 */
public interface CouponRepublishQueryRepository extends Repository<CouponIssueRequest, Long>,
        CouponRepublishQueryRepositoryCustom {
}
