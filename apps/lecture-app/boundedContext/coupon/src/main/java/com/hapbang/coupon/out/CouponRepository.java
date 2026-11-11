package com.hapbang.coupon.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponType;

import jakarta.persistence.LockModeType;

/** 쿠폰 정책 저장소. 동시 상태 변경은 {@code Coupon}의 {@code @Version}이 막는다. 목록 조회는 모두 최신순(같은 시각이면 ID 역순)이다 */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * 쿠폰을 읽고, 상태를 바꾸지 않아도 이 트랜잭션이 커밋될 때 {@code version}을 올린다(OPTIMISTIC_FORCE_INCREMENT).
     * 커밋된 version을 발급 저장소의 fence로 새로 쓸 때(발급 열기·재개) 트랜잭션의 첫 조회로 쓴다.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    Optional<Coupon> findWithVersionIncrementById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findForBudgetRelease(@Param("id") long id);

    /** 예약은 확정됐지만 한 번도 발급을 열지 못한 만료 플랫폼 쿠폰. 결과 불명 예약은 먼저 같은 작업 ID로 확정한다. */
    @Query("""
            select c.id from Coupon c
            where c.type = com.hapbang.coupon.domain.CouponType.PLATFORM
              and c.status = com.hapbang.coupon.domain.CouponStatus.DRAFT
              and c.issueEndAt <= :now and c.issuedCount = 0
              and c.budgetReservationId is not null and c.id > :afterId
              and not exists (select 1 from BudgetWork w where w.couponId = c.id
                  and w.kind = com.hapbang.coupon.domain.BudgetWorkKind.RELEASE and w.target like 'UNISSUED:%')
            order by c.id
            """)
    List<Long> findExpiredDraftBudgetIds(@Param("now") Instant now, @Param("afterId") long afterId, Limit limit);

    /** 미확인 만료 발급 쿠폰이 남은 플랫폼 정책만 고른다. 완료 이력과 판매자 이력을 전역 발급 쿠폰 ID 순으로 다시 읽지 않는다. */
    @Query("""
            select c.id from Coupon c
            where c.type = com.hapbang.coupon.domain.CouponType.PLATFORM and c.id > :afterId
              and exists (select 1 from MemberCoupon mc where mc.couponId = c.id and mc.expiresAt <= :now
                  and mc.status in (com.hapbang.coupon.domain.MemberCouponStatus.USED,
                                    com.hapbang.coupon.domain.MemberCouponStatus.EXPIRED)
                  and (mc.budgetReleaseCheckedStatus is null or mc.budgetReleaseCheckedStatus <> mc.status))
            order by c.id
            """)
    List<Long> findPlatformMemberBudgetCouponIds(@Param("now") Instant now, @Param("afterId") long afterId, Limit limit);

    /**
     * 확정 발급 수를 "확정 발급 수 &lt; 총수량"이고 쿠폰의 epoch가 요청의 epoch와 같을 때만 1 올린다(발급 확정 트랜잭션의 마지막). 바꾼 행 수
     * (1 또는 0)를 돌려주고 해석은 {@code IssueCountRule.interpret}가 한다. 쿠폰 행을 잠그므로 같은 쿠폰의 확정은 순서대로 커밋된다.
     * <p>
     * epoch 조건은 소비자가 쿠폰을 읽은 뒤 복구 재개가 새 epoch를 커밋한 경우를 막는다. 같은 트랜잭션에서 읽었다는 사실만으로는 동시 epoch
     * 변경을 막지 못하므로, 쿠폰 행을 잠그는 이 UPDATE가 epoch를 다시 확인한다(재개의 epoch 변경이 먼저 커밋되면 잠금을 얻은 뒤 바뀐 행으로
     * 조건을 다시 평가해 0행이다). 0행이면 호출자가 쿠폰을 다시 읽어 epoch 변경인지 총량 초과인지 가린다.
     * <p>
     * {@code version}은 올리지 않는다(발급 확정이 관리자 상태 변경의 낙관적 락·Redis fence를 흔들지 않게). 그래서 엔티티 UPDATE는
     * {@code issued_count}를 쓰지 않는다({@code Coupon.issuedCount}의 updatable = false). 엔티티가 이 컬럼을 쓰지 않으므로 native로 둔다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update coupon.coupon set issued_count = issued_count + 1
            where id = :couponId and issued_count < quantity and epoch = :epoch
            """, nativeQuery = true)
    int incrementIssuedCount(@Param("couponId") long couponId, @Param("epoch") long epoch);

    /**
     * 이 강의에 지금 받을 수 있는 쿠폰: 발급 중(OPEN)이고 발급 기간 안(시작 ≤ now &lt; 종료)이며, 그 강의 대상이거나 모든 강의 대상.
     * 판정 규칙은 {@code CouponAvailabilityRule.isListedFor}와 같다.
     */
    @Query("""
            select c from Coupon c
            where c.status = com.hapbang.coupon.domain.CouponStatus.OPEN
              and c.issueStartAt <= :now and c.issueEndAt > :now
              and ((c.targetType = com.hapbang.coupon.domain.CouponTargetType.LECTURE and c.lectureId = :lectureId)
                   or c.targetType = com.hapbang.coupon.domain.CouponTargetType.ANY_LECTURE)
            order by c.createdAt desc, c.id desc
            """)
    List<Coupon> findIssuableFor(@Param("lectureId") long lectureId, @Param("now") Instant now, Limit limit);

    /** 판매자가 신청한 판매자 쿠폰. 상태를 주면 그 상태만 */
    default List<Coupon> findSellerCoupons(long sellerId, CouponStatus status, Limit limit) {
        return status == null
                ? findByApplicantIdAndTypeOrderByCreatedAtDescIdDesc(sellerId, CouponType.SELLER, limit)
                : findByApplicantIdAndTypeAndStatusOrderByCreatedAtDescIdDesc(sellerId, CouponType.SELLER, status,
                        limit);
    }

    /** 모든 쿠폰(판매자·플랫폼). 상태를 주면 그 상태만 */
    default List<Coupon> findAllCoupons(CouponStatus status, Limit limit) {
        return status == null
                ? findAllByOrderByCreatedAtDescIdDesc(limit)
                : findByStatusOrderByCreatedAtDescIdDesc(status, limit);
    }

    List<Coupon> findByApplicantIdAndTypeOrderByCreatedAtDescIdDesc(Long applicantId, CouponType type, Limit limit);

    List<Coupon> findByApplicantIdAndTypeAndStatusOrderByCreatedAtDescIdDesc(Long applicantId, CouponType type,
                                                                            CouponStatus status, Limit limit);

    List<Coupon> findAllByOrderByCreatedAtDescIdDesc(Limit limit);

    List<Coupon> findByStatusOrderByCreatedAtDescIdDesc(CouponStatus status, Limit limit);

    /**
     * 이 강의의 가격 인상을 막는 플랫폼 쿠폰이 있는가: 그 강의 대상 플랫폼 쿠폰이 발급 중(OPEN)이거나, 그 쿠폰의 발급 쿠폰 중 RESERVED이거나
     * 만료 전(expiresAt > now) AVAILABLE인 것이 있다. 판매자 쿠폰은 보지 않는다. 중단(SUSPENDED) 쿠폰은 OPEN으로 치지 않는다.
     */
    @Query("""
            select count(c) > 0 from Coupon c
            where c.type = com.hapbang.coupon.domain.CouponType.PLATFORM
              and c.targetType = com.hapbang.coupon.domain.CouponTargetType.LECTURE
              and c.lectureId = :lectureId
              and (c.status = com.hapbang.coupon.domain.CouponStatus.OPEN
                   or exists (select 1 from MemberCoupon mc
                              where mc.couponId = c.id
                                and (mc.status = com.hapbang.coupon.domain.MemberCouponStatus.RESERVED
                                     or (mc.status = com.hapbang.coupon.domain.MemberCouponStatus.AVAILABLE
                                         and mc.expiresAt > :now))))
            """)
    boolean existsPriceRaiseBlocker(@Param("lectureId") long lectureId, @Param("now") Instant now);
}
