package com.hapbang.coupon.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.coupon.domain.CouponUsage;

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
     * 주문 항목의 사용 시도 전부(해제한 이전 세대 포함). 발급 쿠폰 ID → generation 순. 결제·취소 완료 소비자가 이벤트의 사용 시도를
     * 찾는다({@code idx_coupon_usage_order_item_id})
     */
    List<CouponUsage> findByOrderItemIdOrderByMemberCouponIdAscGenerationAsc(long orderItemId);
}
