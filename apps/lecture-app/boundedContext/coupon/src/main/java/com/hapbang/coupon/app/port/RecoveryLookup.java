package com.hapbang.coupon.app.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.RecoveryIssueResult;

/** 복구·Redis 프로세스 점검이 읽는 조회. 모두 쿠폰 스키마 안의 테이블만 읽는다 */
public interface RecoveryLookup {

    /** 쿠폰의 DB 확정 발급 요청 결과 전부(재구축 입력, 처리 epoch 포함). 저장 순서 */
    List<RecoveryIssueResult> findResults(Long couponId);

    /** {@code issueRequestIds} 가운데 DB에 결과가 있는 요청 ID */
    List<String> findExistingRequestIds(Long couponId, Collection<String> issueRequestIds);

    /**
     * Redis 발급 준비를 마친 쿠폰 중 이 상태들이고 발급 종료가 {@code endedAfter}보다 뒤인 쿠폰 ID(프로세스 점검 대상).
     * {@code endedAfter}에 "지금 − key 보관 여유"를 넘기면 Redis key가 만료됐을 쿠폰은 빠진다
     */
    List<Long> findRedisReadyCouponIds(Collection<CouponStatus> statuses, Instant endedAfter);
}
