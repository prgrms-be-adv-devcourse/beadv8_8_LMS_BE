package com.hapbang.coupon.app;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.IssueStatusView;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.IssueStatus;
import com.hapbang.coupon.domain.IssueStatusRule;
import com.hapbang.coupon.domain.IssueStatusRule.DbRecord;
import com.hapbang.coupon.domain.IssueStatusRule.RedisLookup;
import com.hapbang.coupon.out.CouponIssueRequestRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 발급 요청 상태 조회. 발급 요청 ID는 쿠폰 안에서만 유일하므로 DB 최종 결과와 Redis 접수 기록을 모두 (쿠폰, 요청 ID)로 찾는다.
 * DB를 먼저 보고, 없을 때만 Redis를 본다. 판정은 {@link IssueStatusRule}이 한다(다른 회원의 요청은 NOT_FOUND, Redis에 닿지 못하면
 * UNKNOWN).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueStatusQuery {

    private final CouponIssueRequestRepository couponIssueRequestRepository;
    private final CouponIssueStore couponIssueStore;

    public IssueStatusView issueStatus(long couponId, long memberId, String issueRequestId) {
        CouponIssueRequestUseCase.IssueRequestIds.requireValid(issueRequestId);
        Optional<DbRecord> dbRecord = couponIssueRequestRepository
                .findByCouponIdAndIssueRequestId(couponId, issueRequestId)
                .map(CouponIssueRequest::toDbRecord);
        RedisLookup redisLookup = dbRecord.isPresent()
                ? new RedisLookup.Absent()
                : lookupRedis(couponId, issueRequestId);
        IssueStatus status = IssueStatusRule.judge(dbRecord, redisLookup, memberId);
        return new IssueStatusView(issueRequestId, couponId, status);
    }

    private RedisLookup lookupRedis(long couponId, String issueRequestId) {
        try {
            return couponIssueStore.findRequest(couponId, issueRequestId)
                    .<RedisLookup>map(receipt -> new RedisLookup.Found(receipt.memberId()))
                    .orElseGet(RedisLookup.Absent::new);
        } catch (CouponIssueStoreUnavailableException exception) {
            // Redis 장애 동안 조회마다 몰리므로 요청마다 WARN을 남기지 않는다. 장애는 발급 접수의 503(ERROR)으로 보인다
            log.debug("발급 접수 기록을 읽지 못해 UNKNOWN으로 응답한다 couponId={}, issueRequestId={}", couponId, issueRequestId,
                    exception);
            return new RedisLookup.Unavailable();
        }
    }
}
