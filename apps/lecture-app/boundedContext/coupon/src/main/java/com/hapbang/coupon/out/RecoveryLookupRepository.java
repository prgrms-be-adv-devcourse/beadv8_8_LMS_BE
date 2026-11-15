package com.hapbang.coupon.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.RecoveryIssueResult;

/**
 * 복구·Redis 프로세스 점검이 읽는 조회(읽기 전용). 쓰기는 각 엔티티의 저장소가 한다. 모두 쿠폰 스키마 안의 테이블만 읽는다.
 */
public interface RecoveryLookupRepository extends Repository<CouponIssueRequest, Long> {

    /** 쿠폰의 DB 확정 발급 요청 결과 전부(재구축 입력, 처리 epoch 포함). 저장 순서 */
    @Query("""
            select new com.hapbang.coupon.domain.RecoveryIssueResult(r.issueRequestId, r.memberId, r.result, r.acceptedAt,
                r.epoch)
            from CouponIssueRequest r
            where r.couponId = :couponId
            order by r.id
            """)
    List<RecoveryIssueResult> findResults(@Param("couponId") Long couponId);

    /** {@code issueRequestIds} 가운데 DB에 결과가 있는 요청 ID */
    @Query("""
            select r.issueRequestId from CouponIssueRequest r
            where r.couponId = :couponId and r.issueRequestId in :issueRequestIds
            """)
    List<String> findExistingRequestIds(@Param("couponId") Long couponId,
                                        @Param("issueRequestIds") Collection<String> issueRequestIds);

    /**
     * Redis 발급 준비를 마친 쿠폰 중 이 상태들이고 발급 종료가 {@code endedAfter}보다 뒤인 쿠폰 ID(프로세스 점검 대상).
     * {@code endedAfter}에 "지금 − key 보관 여유"를 넘기면 Redis key가 만료됐을 쿠폰은 빠진다
     */
    @Query("""
            select c.id from Coupon c
            where c.redisReady = true and c.status in :statuses and c.issueEndAt > :endedAfter
            order by c.id
            """)
    List<Long> findRedisReadyCouponIds(@Param("statuses") Collection<CouponStatus> statuses,
                                       @Param("endedAfter") Instant endedAfter);
}
