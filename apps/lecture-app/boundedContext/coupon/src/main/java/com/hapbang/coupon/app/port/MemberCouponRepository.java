package com.hapbang.coupon.app.port;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;

/**
 * 발급 쿠폰 저장소. 쿠폰 정책과의 조인은 같은 쿠폰 스키마 안에서만 한다.
 * <p>
 * 조회는 {@link MemberCouponRepositoryCustom}(QueryDSL)이, 상태 전이(조건부 갱신)는 여기의 JPQL이 맡는다.
 */
public interface MemberCouponRepository extends Repository<MemberCoupon, Long>, MemberCouponRepositoryCustom {

    /** 회원당 같은 쿠폰 1장 */
    String MEMBER_COUPON_UNIQUE = "uk_member_coupon_member_coupon";

    // 쓰는 기본 메서드. 구현은 Spring Data JPA 기본 구현(SimpleJpaRepository)이 맡는다
    <S extends MemberCoupon> S saveAndFlush(S entity);

    /** 회원이 이 쿠폰에서 받은 한 장. 발급 확정이 회원 중복(UNIQUE) 위반을 같은 요청의 재전달인지 가를 때 쓴다 */
    Optional<MemberCoupon> findByMemberIdAndCouponId(long memberId, long couponId);

    /**
     * 예약 조건부 갱신: 본인 소유·AVAILABLE·generation이 기대값·만료 전(expiresAt > now)일 때만 RESERVED로 바꾸고 generation을 1 올린다.
     * 바꾼 행 수(1 또는 0)를 돌려준다. 0행을 성공으로 보지 않고 호출자가 {@code findStateById}로 원인을 가른다.
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
}
