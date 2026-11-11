package com.hapbang.coupon.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;

import jakarta.persistence.LockModeType;

/** 쿠폰 사용 시도 저장소 */
public interface CouponUsageRepository extends JpaRepository<CouponUsage, Long> {

    /** 발급 쿠폰의 한 사용 시도는 한 행 */
    String MEMBER_COUPON_GENERATION_UNIQUE = "uk_coupon_usage_member_coupon_generation";
    /** 발급 쿠폰 한 장은 같은 주문 항목에 동시에 한 번만 예약한다(RESERVED만 보는 부분 유일 인덱스). 해제 뒤 재예약은 허용한다 */
    String RESERVED_ORDER_ITEM_MEMBER_COUPON_UNIQUE = "uk_coupon_usage_order_item_member_coupon_reserved";

    /** 예약 작업 하나가 만든 사용 시도. 주문 항목 → 적용 순서 */
    List<CouponUsage> findByReservationWorkIdOrderByOrderItemIdAscApplyOrderAsc(String reservationWorkId);

    /** 주문의 사용 시도 중 이 해제 작업이 해제한 것. 발급 쿠폰 ID 오름차순 */
    List<CouponUsage> findByOrderIdAndReleaseWorkIdOrderByMemberCouponIdAsc(long orderId, String releaseWorkId);

    /** 발급 쿠폰의 한 세대(사용 시도) */
    Optional<CouponUsage> findByMemberCouponIdAndGeneration(long memberCouponId, long generation);

    /**
     * 주문 항목의 사용 시도 전부(해제한 이전 세대 포함)를 행 잠금으로 읽는다. 결제·취소 완료 소비자(Kafka 소비와 관리자 DLT 재처리)가
     * 이벤트의 사용 시도를 찾는다({@code idx_coupon_usage_order_item_id}). 트랜잭션 안에서 부른다.
     * <ul>
     *     <li>같은 주문 항목의 두 소비를 차례로 처리하게 한다. 뒤에 온 쪽은 앞의 커밋을 읽고 판정한다. 잠그지 않으면 늦게 읽은 쪽의 옛
     *     엔티티 UPDATE(전체 컬럼)가 앞에서 커밋한 상태·사실을 덮어쓴다.</li>
     *     <li>발급 쿠폰 ID → generation 순으로 잠가 여러 행을 잠가도 교착이 없다.</li>
     * </ul>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from CouponUsage u
            where u.orderItemId = :orderItemId
            order by u.memberCouponId asc, u.generation asc
            """)
    List<CouponUsage> findForUpdateByOrderItemId(@Param("orderItemId") long orderItemId);

    /**
     * 회원의 쿠폰 사용 이력. 상태를 주면 그 상태만. 예약 최신순(같은 시각이면 ID 역순)으로 고정하고 {@code pageable}의 정렬은 쓰지 않는다
     * (정렬 없는 {@code Pageable}을 넘긴다). 쿠폰 정책과의 조인은 같은 쿠폰 스키마 안에서만 한다
     * ({@code idx_coupon_usage_member_reserved_at}).
     */
    default Page<UsedCoupon> findMyUsages(long memberId, CouponUsageStatus status, Pageable pageable) {
        return status == null ? findAllUsedBy(memberId, pageable) : findUsedByStatus(memberId, status, pageable);
    }

    @Query(value = """
            select new com.hapbang.coupon.out.UsedCoupon(u, c)
            from CouponUsage u join Coupon c on c.id = u.couponId
            where u.memberId = :memberId
            order by u.reservedAt desc, u.id desc
            """, countQuery = """
            select count(u) from CouponUsage u where u.memberId = :memberId
            """)
    Page<UsedCoupon> findAllUsedBy(@Param("memberId") long memberId, Pageable pageable);

    @Query(value = """
            select new com.hapbang.coupon.out.UsedCoupon(u, c)
            from CouponUsage u join Coupon c on c.id = u.couponId
            where u.memberId = :memberId and u.status = :status
            order by u.reservedAt desc, u.id desc
            """, countQuery = """
            select count(u) from CouponUsage u where u.memberId = :memberId and u.status = :status
            """)
    Page<UsedCoupon> findUsedByStatus(@Param("memberId") long memberId, @Param("status") CouponUsageStatus status,
                                      Pageable pageable);
}
