package com.hapbang.shared.lecture.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.modulith.events.Externalized;

/**
 * 쿠폰을 쓴 주문 항목의 결제가 끝났다. 쿠폰 모듈은 이 이벤트로 예약한 쿠폰을 사용 완료로 바꾼다.
 * 쿠폰이 있는 항목만, 결제 완료를 기록하는 트랜잭션에서 한 번 발행한다. key는 {@code order-item:<주문 항목 ID>}라
 * 같은 항목의 취소 완료({@link OrderItemCancelCompletedEvent})와 순서가 지켜진다.
 * eventId는 항목마다 결정적({@value #EVENT_ID_PREFIX}{@code <주문 항목 ID>})이라 재발행해도 같은 값이고, 받는 쪽은 이 값으로 중복을 거른다.
 *
 * @param eventId     이벤트 ID. {@link #eventIdOf(Long)}로 만든다
 * @param orderId     주문 ID
 * @param orderItemId 주문 항목 ID
 * @param memberId    구매자 회원 ID
 * @param usages      이 항목에 쓴 쿠폰의 사용 시도. 한 개 이상이다
 * @param paidAt      결제 완료 시각
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record OrderItemPaidEvent(
        String eventId,
        Long orderId,
        Long orderItemId,
        Long memberId,
        List<Usage> usages,
        Instant paidAt
) {

    /** {@code eventType} 헤더 값. 레코드 이름을 바꿔도 그대로 둔다(받는 쪽 계약) */
    public static final String EVENT_TYPE = "OrderItemPaidEvent";

    /** eventId 접두어. 뒤에 주문 항목 ID가 붙는다 */
    public static final String EVENT_ID_PREFIX = "order-item-paid:";

    public OrderItemPaidEvent {
        Objects.requireNonNull(eventId, "eventId는 필수입니다.");
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(orderItemId, "orderItemId는 필수입니다.");
        Objects.requireNonNull(memberId, "memberId는 필수입니다.");
        Objects.requireNonNull(usages, "usages는 필수입니다.");
        Objects.requireNonNull(paidAt, "paidAt은 필수입니다.");
        if (usages.isEmpty()) {
            throw new IllegalArgumentException("usages는 한 개 이상이어야 합니다.");
        }
        usages = List.copyOf(usages);
    }

    /** eventId를 규칙대로 채워 만든다 */
    public static OrderItemPaidEvent of(Long orderId, Long orderItemId, Long memberId, List<Usage> usages,
                                        Instant paidAt) {
        return new OrderItemPaidEvent(eventIdOf(orderItemId), orderId, orderItemId, memberId, usages, paidAt);
    }

    /** 주문 항목의 결제 완료 eventId: {@value #EVENT_ID_PREFIX}{@code <주문 항목 ID>} */
    public static String eventIdOf(Long orderItemId) {
        return EVENT_ID_PREFIX + Objects.requireNonNull(orderItemId, "orderItemId는 필수입니다.");
    }

    /**
     * 쿠폰 사용 시도 하나.
     *
     * @param memberCouponId 발급 쿠폰 ID
     * @param generation     예약으로 오른 generation(쿠폰 예약 응답의 reservedGeneration)
     */
    public record Usage(
            long memberCouponId,
            long generation
    ) {
    }
}
