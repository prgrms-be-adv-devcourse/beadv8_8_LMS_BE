package com.hapbang.lecture.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.lecture.out.OrderItemCouponRepository;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 모듈에 알리는 주문 항목의 결제 완료·취소 완료 이벤트를 낸다. 결제 완료·반환 완료를 기록하는 트랜잭션 안에서 부른다.
 * 이벤트는 그 트랜잭션의 Modulith 발행 기록에 남고 커밋 뒤에 Kafka로 간다. 롤백되면 나가지 않는다.
 * <ul>
 *     <li>쿠폰을 쓴 항목(주문 쿠폰 계산 근거가 있는 항목)만, 항목마다 하나 낸다. 쿠폰 없는 주문은 내지 않는다.</li>
 *     <li>사용 시도는 발급 쿠폰 ID와 예약 generation이다.</li>
 *     <li>시각은 기록하는 트랜잭션의 지금 시각(KST)을 Instant로 바꾼 값이다.</li>
 *     <li>플랫폼 부담이 아직 없어 예산 복구 결과는 늘 빈 목록이다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCouponEvents {

    /** 쿠폰을 쓴 항목에 예약 generation이 없어 이벤트를 내지 못했을 때 붙이는 alert 표식. 쿠폰 사용 시도가 바뀌지 않고 남는다 */
    static final String MISSING_GENERATION_LOG = "[LECTURE_COUPON_EVENT_MISSING_GENERATION]";

    private final OrderItemCouponRepository orderItemCouponRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 주문의 결제가 끝났다. 쿠폰을 쓴 항목마다 결제 완료 이벤트를 낸다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void itemsPaid(LectureOrder order, LocalDateTime now) {
        Map<Long, List<OrderItemCoupon>> coupons = couponsByItem(order.getItems());
        for (OrderItem item : order.getItems()) {
            List<OrderItemCoupon> itemCoupons = coupons.get(item.getId());
            if (itemCoupons == null || !hasGenerations(item, itemCoupons)) {
                continue;
            }
            eventPublisher.publishEvent(OrderItemPaidEvent.of(order.getId(), item.getId(), order.getBuyerUserId(),
                    itemCoupons.stream().map(coupon -> new OrderItemPaidEvent.Usage(coupon.getMemberCouponId(),
                            coupon.getReservedGeneration())).toList(), toInstant(now)));
        }
    }

    /**
     * 항목의 구매 취소·이의 제기 반환이 끝났다. 쿠폰을 쓴 항목이면 취소 완료 이벤트를 낸다.
     *
     * @param billingReleaseRequestId 구매자 HP를 돌려준 지갑 작업의 요청 ID. 작업 없이 끝났으면(구매자 금액 0) {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void itemCancelCompleted(LectureOrder order, OrderItem item, String billingReleaseRequestId,
                                    LocalDateTime now) {
        List<OrderItemCoupon> itemCoupons = couponsByItem(List.of(item)).get(item.getId());
        if (itemCoupons == null || !hasGenerations(item, itemCoupons)) {
            return;
        }
        eventPublisher.publishEvent(OrderItemCancelCompletedEvent.of(order.getId(), item.getId(),
                order.getBuyerUserId(), itemCoupons.stream()
                        .map(coupon -> new OrderItemCancelCompletedEvent.Usage(coupon.getMemberCouponId(),
                                coupon.getReservedGeneration()))
                        .toList(), billingReleaseRequestId, List.of(), toInstant(now)));
    }

    private Map<Long, List<OrderItemCoupon>> couponsByItem(Collection<OrderItem> items) {
        return orderItemCouponRepository.findByOrderItemIdInOrderByOrderItemIdAscApplyOrderAsc(
                        items.stream().map(OrderItem::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(OrderItemCoupon::getOrderItemId));
    }

    /** 결제·반환까지 온 쿠폰 항목은 예약 결과를 저장한 뒤라 generation이 있다. 없으면 쿠폰을 바꿀 수 없어 이벤트를 내지 않는다 */
    private static boolean hasGenerations(OrderItem item, List<OrderItemCoupon> coupons) {
        if (coupons.stream().allMatch(coupon -> coupon.getReservedGeneration() != null)) {
            return true;
        }
        log.error(MISSING_GENERATION_LOG + " 쿠폰을 쓴 항목에 예약 generation이 없어 쿠폰 이벤트를 내지 않았다. 운영자가 쿠폰 사용 "
                + "시도를 확인한다 orderItemId={}", item.getId());
        return false;
    }

    private static Instant toInstant(LocalDateTime now) {
        return now.atZone(LectureConfig.ZONE).toInstant();
    }
}
