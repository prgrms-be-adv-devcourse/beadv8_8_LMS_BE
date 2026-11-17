package com.hapbang.coupon.app.port;

import org.springframework.data.repository.Repository;

import com.hapbang.coupon.domain.CouponIssueRequest;

/**
 * 대사용 발급 요청 결과 읽기(읽기 전용). 쓰기와 단건 조회는 {@link CouponIssueRequestRepository}가 맡는다.
 * 조회는 {@link IssueResultReadRepositoryCustom}(QueryDSL)이 맡는다.
 */
public interface IssueResultReadRepository extends Repository<CouponIssueRequest, Long>, IssueResultReadRepositoryCustom {
}
