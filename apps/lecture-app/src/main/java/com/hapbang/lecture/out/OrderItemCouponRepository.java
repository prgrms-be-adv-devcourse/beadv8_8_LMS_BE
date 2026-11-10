package com.hapbang.lecture.out;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.lecture.domain.OrderItemCoupon;

/** 주문 항목에 쓴 쿠폰의 계산 근거. 행을 지우지 않는다 */
public interface OrderItemCouponRepository extends JpaRepository<OrderItemCoupon, Long> {

    /** 주문 항목들에 쓴 쿠폰. 항목 ID, 항목 안 적용 순서대로다 */
    List<OrderItemCoupon> findByOrderItemIdInOrderByOrderItemIdAscApplyOrderAsc(Collection<Long> orderItemIds);
}
