package com.hapbang.coupon.out;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponStatus;

/**
 * 재발행 배치의 읽기(읽기 전용). 쓰기와 단건 조회는 {@link CouponRepository}·{@link CouponIssueRequestRepository}가 맡는다.
 */
public interface CouponRepublishQueryRepository extends Repository<CouponIssueRequest, Long> {

    /** Redis 발급 준비를 마친 쿠폰 중 상태가 {@code statuses}인 것. 쿠폰 ID 순서다 */
    @Query("""
            select new com.hapbang.coupon.out.RepublishTargetCoupon(c.id, c.status, c.epoch)
            from Coupon c
            where c.redisReady = true and c.status in :statuses
            order by c.id
            """)
    List<RepublishTargetCoupon> findTargets(@Param("statuses") Collection<CouponStatus> statuses);

    /** {@code issueRequestIds} 중 DB에 결과가 있는 요청 ID */
    @Query("""
            select r.issueRequestId from CouponIssueRequest r
            where r.couponId = :couponId and r.issueRequestId in :issueRequestIds
            """)
    List<String> findRecordedIds(@Param("couponId") long couponId,
                                 @Param("issueRequestIds") Collection<String> issueRequestIds);
}
