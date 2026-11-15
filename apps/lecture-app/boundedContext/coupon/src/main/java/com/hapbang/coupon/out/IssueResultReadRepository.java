package com.hapbang.coupon.out;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.CouponIssueRequest;

/**
 * 대사용 발급 요청 결과 읽기(읽기 전용). 쓰기와 단건 조회는 {@link CouponIssueRequestRepository}가 맡는다.
 */
public interface IssueResultReadRepository extends Repository<CouponIssueRequest, Long> {

    /** 쿠폰의 발급 요청 결과 전부. 행 수는 쿠폰 수량(쿠폰별 상한) 안이다 */
    @Query("""
            select new com.hapbang.coupon.out.IssueResultRow(r.issueRequestId, r.memberId, r.result, r.epoch)
            from CouponIssueRequest r
            where r.couponId = :couponId
            """)
    List<IssueResultRow> findRowsByCouponId(@Param("couponId") long couponId);
}
