package com.hapbang.coupon.app.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;

import com.hapbang.coupon.domain.MemberCouponStatus;

/**
 * 발급 쿠폰 조회. 쿠폰 정책과의 조인은 같은 쿠폰 스키마 안에서만 한다. 상태 전이(예약·해제·사용·복원·만료·예산 해제 계획)는 저장소의
 * 조건부 갱신이 맡는다.
 * <p>
 * {@link MemberCouponRepository}의 QueryDSL 조회 조각이다. 구현은 {@code out}의 {@code MemberCouponRepositoryCustomImpl}이다.
 */
public interface MemberCouponRepositoryCustom {

    /** 이 쿠폰들 중 회원이 발급 쿠폰을 가진 쿠폰 ID(상태와 관계없이). {@code couponIds}가 비면 부르지 않는다 */
    List<Long> findCouponIdsOwnedBy(long memberId, Collection<Long> couponIds);

    /** 회원의 발급 쿠폰. 상태를 주면 그 상태만. 발급 최신순(같은 시각이면 ID 역순) */
    List<OwnedCoupon> findMyCoupons(long memberId, MemberCouponStatus status);

    /**
     * 이 강의에 지금 쓸 수 있는 회원의 발급 쿠폰: AVAILABLE이고 만료 전(expiresAt > now)이며, 쿠폰이 그 강의 대상이거나 모든 강의 대상.
     * 쿠폰 정책 상태(중단·종료)로는 거르지 않는다(발급 중단은 신규 발급만 막는다). 만료 임박순.
     */
    List<OwnedCoupon> findUsableFor(long memberId, long lectureId, Instant now);

    /** 이 발급 쿠폰들과 각 쿠폰 정책(소유·상태와 관계없이). 계산·예약이 고른 쿠폰을 검증할 때 쓴다. {@code ids}가 비면 부르지 않는다 */
    List<OwnedCoupon> findOwnedByIds(Collection<Long> ids);

    /** 지금 DB의 발급 쿠폰 상태(엔티티가 아닌 값). 조건부 갱신이 0행일 때 원인을 가르거나 해제 결과를 읽을 때 쓴다 */
    Optional<MemberCouponState> findStateById(long id);

    /**
     * 만료 배치 대상과 다음 조회 위치: 기한이 지난(expiresAt <= now) 판매자·플랫폼의 모든 AVAILABLE 발급 쿠폰을 만료 시각 → ID 순으로
     * {@code limit}개 고른다. 조건은 {@code idx_member_coupon_status_expires_at}(status, expires_at)의 앞 컬럼 등호·뒤 컬럼 범위라 그
     * 인덱스를 탈 수 있다. {@code afterExpiresAt}이 없으면 첫 페이지다. 있으면 같은 만료 시각의 ID도 커서에 포함하여 실패한 행을
     * 건너뛰면서 기한 순서를 유지한다. 첫 페이지는 커서 조건을 넣지 않아 NULL timestamp 매개변수의 DB 타입 추론에 의존하지 않는다.
     */
    List<MemberCouponExpiryTarget> findExpiredCouponTargets(Instant now, Instant afterExpiresAt, long afterId,
                                                            Limit limit);

    /** 예산 해제 CAS 입력. 엔티티 캐시 대신 현재 DB 값을 읽는다 */
    Optional<MemberCouponBudgetState> findBudgetState(long id);

    /**
     * 플랫폼 정책의 미확인 만료 발급 쿠폰(USED·EXPIRED이고 그 상태로 예산 해제를 확인하지 않음). 정책·만료 기한 범위만 읽고, 후속 페이지는
     * (expiresAt, id) 복합 커서로 진행한다. {@code afterExpiresAt}이 없으면 커서 조건 없는 첫 페이지다.
     */
    List<MemberCouponExpiryTarget> findExpiredPlatformBudgetTargets(long couponId, Instant now,
            Instant afterExpiresAt, long afterId, Limit limit);
}
