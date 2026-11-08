package com.hapbang.coupon.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
