package com.hapbang.lecture.out;

import com.hapbang.lecture.domain.OrderItem;

/** 주문 항목과 그 주문 ID (서비스 간 HP 대사, D74). 주문을 읽지 않고 ID만 얻는다 */
public record OrderItemWithOrderId(OrderItem item, Long orderId) {
}
