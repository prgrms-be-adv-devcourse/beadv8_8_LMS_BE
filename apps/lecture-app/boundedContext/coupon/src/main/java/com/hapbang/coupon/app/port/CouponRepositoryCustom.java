package com.hapbang.coupon.app.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;

/**
 * 쿠폰 정책 조회. 목록은 모두 최신순(같은 시각이면 ID 역순)이다
 * <p>
 * {@link CouponRepository}의 QueryDSL 조회 조각이다. 구현은 {@code out}의 {@code CouponRepositoryCustomImpl}이다.
 */
public interface CouponRepositoryCustom {

    /**
     * 예약은 확정됐지만 한 번도 발급을 열지 못한 만료 플랫폼 쿠폰 ID(DRAFT, 발급 종료 ≤ now, 확정 발급 0장, 예산 예약 ID 있음).
     * 미발급분 해제 작업({@code UNISSUED:} 대상)이 이미 있는 쿠폰은 뺀다. 결과 불명 예약은 먼저 같은 작업 ID로 확정한다. 쿠폰 ID 순 cursor
     */
    List<Long> findExpiredDraftBudgetIds(Instant now, long afterId, Limit limit);

    /** 미확인 만료 발급 쿠폰이 남은 플랫폼 정책 ID만 고른다. 완료 이력과 판매자 이력을 전역 발급 쿠폰 ID 순으로 다시 읽지 않는다. 쿠폰 ID 순 cursor */
    List<Long> findPlatformMemberBudgetCouponIds(Instant now, long afterId, Limit limit);

    /**
     * 이 강의에 지금 받을 수 있는 쿠폰: 발급 중(OPEN)이고 발급 기간 안(시작 ≤ now &lt; 종료)이며, 그 강의 대상이거나 모든 강의 대상.
     * 판정 규칙은 {@code CouponAvailabilityRule.isListedFor}와 같다.
     */
    List<Coupon> findIssuableFor(long lectureId, Instant now, Limit limit);

    /**
     * 이 강의의 가격 인상을 막는 플랫폼 쿠폰이 있는가: 그 강의 대상 플랫폼 쿠폰이 발급 중(OPEN)이거나, 그 쿠폰의 발급 쿠폰 중 RESERVED이거나
     * 만료 전(expiresAt > now) AVAILABLE인 것이 있다. 판매자 쿠폰은 보지 않는다. 중단(SUSPENDED) 쿠폰은 OPEN으로 치지 않는다.
     */
    boolean existsPriceRaiseBlocker(long lectureId, Instant now);

    /** 재발행 대상: Redis 발급 준비를 마친 쿠폰 중 상태가 {@code statuses}인 것. 쿠폰 ID 순서다 */
    List<RepublishTargetCoupon> findRepublishTargets(Collection<CouponStatus> statuses);

    /**
     * Redis 발급 준비를 마친 쿠폰 중 이 상태들이고 발급 종료가 {@code endedAfter}보다 뒤인 쿠폰 ID(Redis 프로세스 점검 대상).
     * {@code endedAfter}에 "지금 − key 보관 여유"를 넘기면 Redis key가 만료됐을 쿠폰은 빠진다
     */
    List<Long> findRedisReadyIds(Collection<CouponStatus> statuses, Instant endedAfter);
}
