package com.hapbang.coupon.app;

import java.time.Clock;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.IssueStatusView;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponRecoveryStore;
import com.hapbang.coupon.app.port.CouponRecoveryStore.IssueControlState;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.IssueStatus;
import com.hapbang.coupon.domain.IssueStatusRule;
import com.hapbang.coupon.domain.IssueStatusRule.DbRecord;
import com.hapbang.coupon.domain.IssueStatusRule.RedisLookup;
import com.hapbang.coupon.domain.RecoveryRun;
import com.hapbang.coupon.out.CouponIssueRequestRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.RecoveryRunRepository;
import com.hapbang.coupon.out.redis.CouponRedisProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 발급 요청 상태 조회. 발급 요청 ID는 쿠폰 안에서만 유일하므로 DB 최종 결과와 Redis 접수 기록을 모두 (쿠폰, 요청 ID)로 찾는다.
 * DB를 먼저 보고, 없을 때만 Redis를 본다. 판정은 {@link IssueStatusRule}이 한다(다른 회원의 요청은 NOT_FOUND, Redis에 닿지 못하면
 * UNKNOWN).
 * <p>
 * Redis에 접수 기록이 없을 때 그것이 "접수하지 않았다"인지 "기록이 사라졌다"인지 가린다. 다음이면 유실로 보고 UNKNOWN이다(접수 경로가
 * 아니라 DB를 읽어도 된다).
 * <ul>
 *     <li>issue-control에 복구 차단(hold=RECOVERY)이 있다. 프로세스 변경 점검이나 복구가 접수 기록을 믿을 수 없다고 보고 막은 상태다
 *     (복구 실행이 아직 없어도 그렇다).</li>
 *     <li>쿠폰이 OPEN·SUSPENDED이고 Redis 준비를 마쳤는데 issue-control이 없거나 ready≠1이다(Redis 유실, 또는 복구 차단 중). 중단 중에도 그 전의
 *     접수 기록이 남아 있어야 하므로 OPEN과 같게 본다.</li>
 *     <li>그 쿠폰에서 접수 기록 유실이 의심된 가장 최근 복구가 재개 전(진행 중이거나 취소됨)이거나, 재개했지만 그 쿠폰의 Redis 기록 보관
 *     기간(쿠폰별 key 만료 시각: issue-control의 keysExpireAt, 없으면 DB 쿠폰의 발급 종료 + key 만료 여유)이 끝나지 않았다
 *     ({@link RecoveryRun#leavesUnknownAt}). 재구축한 Redis에는 결과가 증명된 요청만 있어, 유실된 접수는 Redis·DB 어디에도 없다.
 *     보관 기간이 끝나면 NOT_FOUND다.</li>
 * </ul>
 */
@Slf4j
@Service
public class CouponIssueStatusQuery {

    private final CouponIssueRequestRepository couponIssueRequestRepository;
    private final CouponIssueStore couponIssueStore;
    private final CouponRepository couponRepository;
    private final CouponRecoveryStore couponRecoveryStore;
    private final RecoveryRunRepository recoveryRunRepository;
    private final CouponRedisProperties redisProperties;
    private final Clock clock;

    public CouponIssueStatusQuery(CouponIssueRequestRepository couponIssueRequestRepository,
                                  CouponIssueStore couponIssueStore, CouponRepository couponRepository,
                                  CouponRecoveryStore couponRecoveryStore, RecoveryRunRepository recoveryRunRepository,
                                  CouponRedisProperties redisProperties,
                                  @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponIssueRequestRepository = couponIssueRequestRepository;
        this.couponIssueStore = couponIssueStore;
        this.couponRepository = couponRepository;
        this.couponRecoveryStore = couponRecoveryStore;
        this.recoveryRunRepository = recoveryRunRepository;
        this.redisProperties = redisProperties;
        this.clock = clock;
    }

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
            Optional<CouponIssueStore.IssueReceipt> receipt = couponIssueStore.findRequest(couponId, issueRequestId);
            if (receipt.isPresent()) {
                return new RedisLookup.Found(receipt.get().memberId());
            }
            return requestsMayBeLost(couponId) ? new RedisLookup.Unavailable() : new RedisLookup.Absent();
        } catch (CouponIssueStoreUnavailableException exception) {
            // Redis 장애 동안 조회마다 몰리므로 요청마다 WARN을 남기지 않는다. 장애는 발급 접수의 503(ERROR)으로 보인다
            log.debug("발급 접수 기록을 읽지 못해 UNKNOWN으로 응답한다 couponId={}, issueRequestId={}", couponId, issueRequestId,
                    exception);
            return new RedisLookup.Unavailable();
        }
    }

    /** Redis에 기록이 없다는 것을 "접수하지 않았다"로 믿을 수 없는가 */
    private boolean requestsMayBeLost(long couponId) {
        IssueControlState control = couponRecoveryStore.issueControlState(couponId);
        if (control.recoveryHold()) {
            return true;
        }
        Optional<Coupon> coupon = couponRepository.findById(couponId);
        boolean recoveryLeavesUnknown = recoveryRunRepository.findFirstByCouponIdAndRequestsLostTrueOrderByIdDesc(couponId)
                .map(run -> run.leavesUnknownAt(clock.instant(), RecoveryRun.recordsRetainedUntil(control.keysExpireAt(),
                        coupon.map(Coupon::getIssueEndAt).orElse(null), redisProperties.keysExpireMargin())))
                .orElse(false);
        if (recoveryLeavesUnknown) {
            return true;
        }
        boolean acceptingAndPrepared = coupon
                .map(found -> (found.getStatus() == CouponStatus.OPEN || found.getStatus() == CouponStatus.SUSPENDED)
                        && found.isRedisReady())
                .orElse(false);
        return acceptingAndPrepared && (!control.present() || !control.ready());
    }
}
