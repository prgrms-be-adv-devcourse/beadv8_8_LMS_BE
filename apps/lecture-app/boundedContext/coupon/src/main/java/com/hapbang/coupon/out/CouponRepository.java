package com.hapbang.coupon.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
