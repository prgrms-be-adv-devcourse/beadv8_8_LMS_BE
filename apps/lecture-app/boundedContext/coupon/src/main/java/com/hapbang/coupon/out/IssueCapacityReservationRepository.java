package com.hapbang.coupon.out;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.IssueCapacityReservation;

/**
 * 쿠폰별 발급 저장소 용량 예약. 합계({@link IssueCapacityRepository})와 같은 트랜잭션에서 쓴다.
 */
public interface IssueCapacityReservationRepository extends JpaRepository<IssueCapacityReservation, Long> {

    /**
     * 쿠폰의 예약 행을 없을 때만 만든다. 같은 쿠폰을 동시에 열면 뒤에 온 쪽은 앞의 커밋을 기다린 뒤 0을 받는다.
     *
     * @return 1 만듦, 0 이미 있음
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            insert into coupon.issue_capacity_reservation (coupon_id, quantity, reserved_at)
            values (:couponId, :quantity, :now)
            on conflict (coupon_id) do nothing
            """)
    int insertIfAbsent(@Param("couponId") long couponId, @Param("quantity") int quantity, @Param("now") Instant now);

    /**
     * 쿠폰의 예약 행을 지운다.
     *
     * @return 1 지움, 0 없음(이미 풀었다)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from IssueCapacityReservation r where r.couponId = :couponId")
    int deleteByCouponId(@Param("couponId") long couponId);

    /**
     * 예약 시각을 지금으로 바꾼다. 발급 열기 재시도가 앞 시도의 예약을 다시 쓸 때 불러, 대사 배치가 진행 중인 열기의 예약을 고아로 풀지 않게 한다.
     *
     * @return 1 바꿈, 0 예약이 없음(그 사이 풀렸다)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update IssueCapacityReservation r set r.reservedAt = :now where r.couponId = :couponId")
    int touch(@Param("couponId") long couponId, @Param("now") Instant now);

    /**
     * 예약 시각이 기준 시각 이전인 예약 행만 지운다(고아 예약 해제). 그 사이 열기 재시도가 예약 시각을 바꿨으면 지우지 않는다.
     *
     * @return 1 지움, 0 없거나 기준 시각보다 새 예약
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from IssueCapacityReservation r where r.couponId = :couponId and r.reservedAt <= :cutoff")
    int deleteByCouponIdReservedAtOrBefore(@Param("couponId") long couponId, @Param("cutoff") Instant cutoff);

    /** 예약이 있는 쿠폰 ID(대사 대상). 오래된 예약 순 */
    @Query("select r.couponId from IssueCapacityReservation r order by r.reservedAt asc, r.couponId asc")
    List<Long> findCouponIds();
}
