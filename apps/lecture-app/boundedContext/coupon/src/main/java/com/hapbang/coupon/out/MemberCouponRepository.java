package com.hapbang.coupon.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;

/** 발급 쿠폰 저장소. 쿠폰 정책과의 조인은 같은 쿠폰 스키마 안에서만 한다 */
public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {

    /** 회원당 같은 쿠폰 1장 */
    String MEMBER_COUPON_UNIQUE = "uk_member_coupon_member_coupon";
    /** 같은 쿠폰·같은 발급 요청은 1장 */
    String ISSUE_REQUEST_UNIQUE = "uk_member_coupon_issue_request";

    /** 회원이 이 쿠폰에서 받은 한 장. 발급 확정이 회원 중복(UNIQUE) 위반을 같은 요청의 재전달인지 가를 때 쓴다 */
    Optional<MemberCoupon> findByMemberIdAndCouponId(long memberId, long couponId);

    /** 이 쿠폰들 중 회원이 발급 쿠폰을 가진 쿠폰 ID(상태와 관계없이). {@code couponIds}가 비면 부르지 않는다 */
    @Query("""
            select mc.couponId from MemberCoupon mc
            where mc.memberId = :memberId and mc.couponId in :couponIds
            """)
    List<Long> findCouponIdsOwnedBy(@Param("memberId") long memberId,
                                    @Param("couponIds") Collection<Long> couponIds);

    /** 회원의 발급 쿠폰. 상태를 주면 그 상태만. 발급 최신순(같은 시각이면 ID 역순) */
    default List<OwnedCoupon> findMyCoupons(long memberId, MemberCouponStatus status) {
        return status == null ? findAllOwned(memberId) : findOwnedByStatus(memberId, status);
    }

    @Query("""
            select new com.hapbang.coupon.out.OwnedCoupon(mc, c)
            from MemberCoupon mc join Coupon c on c.id = mc.couponId
            where mc.memberId = :memberId
            order by mc.issuedAt desc, mc.id desc
            """)
    List<OwnedCoupon> findAllOwned(@Param("memberId") long memberId);

    @Query("""
            select new com.hapbang.coupon.out.OwnedCoupon(mc, c)
            from MemberCoupon mc join Coupon c on c.id = mc.couponId
            where mc.memberId = :memberId and mc.status = :status
            order by mc.issuedAt desc, mc.id desc
            """)
    List<OwnedCoupon> findOwnedByStatus(@Param("memberId") long memberId,
                                        @Param("status") MemberCouponStatus status);

    /**
     * 이 강의에 지금 쓸 수 있는 회원의 발급 쿠폰: AVAILABLE이고 만료 전(expiresAt > now)이며, 쿠폰이 그 강의 대상이거나 모든 강의 대상.
     * 쿠폰 정책 상태(중단·종료)로는 거르지 않는다(발급 중단은 신규 발급만 막는다). 만료 임박순.
     */
    @Query("""
            select new com.hapbang.coupon.out.OwnedCoupon(mc, c)
            from MemberCoupon mc join Coupon c on c.id = mc.couponId
            where mc.memberId = :memberId
              and mc.status = com.hapbang.coupon.domain.MemberCouponStatus.AVAILABLE
              and mc.expiresAt > :now
              and ((c.targetType = com.hapbang.coupon.domain.CouponTargetType.LECTURE and c.lectureId = :lectureId)
                   or c.targetType = com.hapbang.coupon.domain.CouponTargetType.ANY_LECTURE)
            order by mc.expiresAt asc, mc.id asc
            """)
    List<OwnedCoupon> findUsableFor(@Param("memberId") long memberId, @Param("lectureId") long lectureId,
                                    @Param("now") Instant now);

    /** 이 발급 쿠폰들과 각 쿠폰 정책(소유·상태와 관계없이). 계산·예약이 고른 쿠폰을 검증할 때 쓴다. {@code ids}가 비면 부르지 않는다 */
    @Query("""
            select new com.hapbang.coupon.out.OwnedCoupon(mc, c)
            from MemberCoupon mc join Coupon c on c.id = mc.couponId
            where mc.id in :ids
            """)
    List<OwnedCoupon> findOwnedByIds(@Param("ids") Collection<Long> ids);

    /**
     * 예약 조건부 갱신: 본인 소유·AVAILABLE·generation이 기대값·만료 전(expiresAt > now)일 때만 RESERVED로 바꾸고 generation을 1 올린다.
     * 바꾼 행 수(1 또는 0)를 돌려준다. 0행을 성공으로 보지 않고 호출자가 {@link #findStateById}로 원인을 가른다.
     * <p>
     * 여러 장은 발급 쿠폰 ID 오름차순으로 부른다(교착 회피). 영속성 컨텍스트의 엔티티는 갱신하지 않으므로 같은 트랜잭션에서 엔티티로 다시
     * 읽지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update MemberCoupon mc
            set mc.status = com.hapbang.coupon.domain.MemberCouponStatus.RESERVED,
                mc.generation = mc.generation + 1,
                mc.currentOrderItemId = :orderItemId,
                mc.reservedAt = :now,
                mc.updatedAt = :now
            where mc.id = :id
              and mc.memberId = :memberId
              and mc.status = com.hapbang.coupon.domain.MemberCouponStatus.AVAILABLE
              and mc.generation = :expectedGeneration
              and mc.expiresAt > :now
            """)
    int reserve(@Param("id") long id, @Param("memberId") long memberId,
                @Param("expectedGeneration") long expectedGeneration, @Param("orderItemId") long orderItemId,
                @Param("now") Instant now);

    /**
     * 해제 조건부 갱신: RESERVED이고 generation이 그 사용 시도의 값일 때만, 만료 전(expiresAt > now)이면 AVAILABLE, 아니면 EXPIRED로
     * 바꾸고 현재 항목·예약 시각을 비운다. generation은 그대로다. 바꾼 행 수(1 또는 0)를 돌려준다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update MemberCoupon mc
            set mc.status = case when mc.expiresAt > :now
                                 then com.hapbang.coupon.domain.MemberCouponStatus.AVAILABLE
                                 else com.hapbang.coupon.domain.MemberCouponStatus.EXPIRED end,
                mc.currentOrderItemId = null,
                mc.reservedAt = null,
                mc.updatedAt = :now
            where mc.id = :id
              and mc.status = com.hapbang.coupon.domain.MemberCouponStatus.RESERVED
              and mc.generation = :generation
            """)
    int release(@Param("id") long id, @Param("generation") long generation, @Param("now") Instant now);

    /**
     * 결제 완료 조건부 갱신: RESERVED이고 generation이 그 사용 시도의 값일 때만 USED로 바꾼다. 기한은 보지 않는다(할인은 예약 성공 시점
     * 기준으로 인정한다). 바꾼 행 수(1 또는 0)를 돌려준다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update MemberCoupon mc
            set mc.status = com.hapbang.coupon.domain.MemberCouponStatus.USED,
                mc.usedAt = :now,
                mc.updatedAt = :now
            where mc.id = :id
              and mc.status = com.hapbang.coupon.domain.MemberCouponStatus.RESERVED
              and mc.generation = :generation
            """)
    int markUsed(@Param("id") long id, @Param("generation") long generation, @Param("now") Instant now);

    /**
     * 취소·반환 완료 조건부 갱신: RESERVED·USED이고 generation이 그 사용 시도의 값일 때만, 만료 전(expiresAt > now)이면 AVAILABLE, 아니면
     * EXPIRED로 바꾸고 현재 항목·예약 시각·사용 시각을 비운다. generation은 그대로다. 바꾼 행 수(1 또는 0)를 돌려준다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update MemberCoupon mc
            set mc.status = case when mc.expiresAt > :now
                                 then com.hapbang.coupon.domain.MemberCouponStatus.AVAILABLE
                                 else com.hapbang.coupon.domain.MemberCouponStatus.EXPIRED end,
                mc.currentOrderItemId = null,
                mc.reservedAt = null,
                mc.usedAt = null,
                mc.updatedAt = :now
            where mc.id = :id
              and mc.status in (com.hapbang.coupon.domain.MemberCouponStatus.RESERVED,
                                com.hapbang.coupon.domain.MemberCouponStatus.USED)
              and mc.generation = :generation
            """)
    int restore(@Param("id") long id, @Param("generation") long generation, @Param("now") Instant now);

    /** 지금 DB의 발급 쿠폰 상태(엔티티가 아닌 값). 조건부 갱신이 0행일 때 원인을 가르거나 해제 결과를 읽을 때 쓴다 */
    @Query("""
            select new com.hapbang.coupon.out.MemberCouponState(mc.id, mc.memberId, mc.status, mc.generation,
                mc.expiresAt, mc.currentOrderItemId)
            from MemberCoupon mc where mc.id = :id
            """)
    Optional<MemberCouponState> findStateById(@Param("id") long id);

    /**
     * 만료 배치 대상: 기한이 지난(expiresAt <= now) 판매자·플랫폼의 모든 AVAILABLE 발급 쿠폰.
     * 만료 시각 → ID 순으로 {@code limit}개의 ID를 고른다. 조건은 {@code idx_member_coupon_status_expires_at}
     * (status, expires_at)의 앞 컬럼 등호·뒤 컬럼 범위라 그 인덱스를 탈 수 있다.
     */
    default List<Long> findExpiredCouponIds(Instant now, Limit limit) {
        return findExpiredCouponTargets(now, null, 0, limit).stream().map(MemberCouponExpiryTarget::id).toList();
    }

    /** 첫 페이지는 커서 없는 쿼리를 사용해 NULL timestamp 매개변수의 DB 타입 추론에 의존하지 않는다. */
    default List<MemberCouponExpiryTarget> findExpiredCouponTargets(Instant now, Instant afterExpiresAt,
                                                                   long afterId, Limit limit) {
        return afterExpiresAt == null ? findFirstExpiredCouponTargets(now, limit)
                : findNextExpiredCouponTargets(now, afterExpiresAt, afterId, limit);
    }

    @Query("""
            select new com.hapbang.coupon.out.MemberCouponExpiryTarget(mc.id, mc.expiresAt) from MemberCoupon mc
            where mc.status = com.hapbang.coupon.domain.MemberCouponStatus.AVAILABLE
              and mc.expiresAt <= :now
            order by mc.expiresAt asc, mc.id asc
            """)
    List<MemberCouponExpiryTarget> findFirstExpiredCouponTargets(@Param("now") Instant now, Limit limit);

    /** 같은 만료 시각의 ID도 커서에 포함하여 실패한 행을 건너뛰면서 기한 순서를 유지한다. */
    @Query("""
            select new com.hapbang.coupon.out.MemberCouponExpiryTarget(mc.id, mc.expiresAt) from MemberCoupon mc
            where mc.status = com.hapbang.coupon.domain.MemberCouponStatus.AVAILABLE
              and mc.expiresAt <= :now
              and (mc.expiresAt > :afterExpiresAt
                   or (mc.expiresAt = :afterExpiresAt and mc.id > :afterId))
            order by mc.expiresAt asc, mc.id asc
            """)
    List<MemberCouponExpiryTarget> findNextExpiredCouponTargets(@Param("now") Instant now,
            @Param("afterExpiresAt") Instant afterExpiresAt, @Param("afterId") long afterId, Limit limit);

    /**
     * 만료 조건부 갱신: 고른 ID 중 아직 AVAILABLE이고 기한이 지난(expiresAt <= now) 것만 EXPIRED로 바꾼다. generation은 그대로다. 고른 뒤
     * 예약된 쿠폰은 바뀌지 않는다. 바꾼 행 수를 돌려준다. {@code ids}가 비면 부르지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update MemberCoupon mc
            set mc.status = com.hapbang.coupon.domain.MemberCouponStatus.EXPIRED,
                mc.updatedAt = :now
            where mc.id in :ids
              and mc.status = com.hapbang.coupon.domain.MemberCouponStatus.AVAILABLE
              and mc.expiresAt <= :now
            """)
    int expire(@Param("ids") Collection<Long> ids, @Param("now") Instant now);

    @Query("""
            select new com.hapbang.coupon.out.MemberCouponBudgetState(mc.id, mc.couponId, mc.status,
                mc.generation, mc.expiresAt, mc.budgetReleasePlannedHp, mc.budgetReleaseCheckedStatus)
            from MemberCoupon mc where mc.id = :id
            """)
    Optional<MemberCouponBudgetState> findBudgetState(@Param("id") long id);

    @Modifying(flushAutomatically = true)
    @Query("""
            update MemberCoupon mc set mc.budgetReleasePlannedHp = :planned,
                mc.budgetReleaseCheckedStatus = :status, mc.updatedAt = :now
            where mc.id = :id and mc.generation = :generation and mc.status = :status
                and mc.budgetReleasePlannedHp = :before and mc.expiresAt <= :now
                and (mc.budgetReleaseCheckedStatus is null or mc.budgetReleaseCheckedStatus <> mc.status)
            """)
    int planBudgetRelease(@Param("id") long id, @Param("generation") long generation,
            @Param("status") MemberCouponStatus status, @Param("before") long before,
            @Param("planned") long planned, @Param("now") Instant now);

    /** 플랫폼 후보 정책을 고른 뒤 그 정책의 키 범위만 읽는다. 전역 ID 순서로 판매자 만료 이력을 훑지 않는다. */
    @Query("""
            select mc.id from MemberCoupon mc
            where mc.couponId = :couponId and mc.id > :afterId and mc.expiresAt <= :now
              and mc.status in (com.hapbang.coupon.domain.MemberCouponStatus.USED,
                                com.hapbang.coupon.domain.MemberCouponStatus.EXPIRED)
              and (mc.budgetReleaseCheckedStatus is null or mc.budgetReleaseCheckedStatus <> mc.status)
            order by mc.id
            """)
    List<Long> findExpiredPlatformBudgetIds(@Param("couponId") long couponId, @Param("now") Instant now,
            @Param("afterId") long afterId, Limit limit);
}
