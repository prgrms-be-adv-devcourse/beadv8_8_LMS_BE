package com.hapbang.coupon.app;

import java.util.Optional;

import com.hapbang.shared.coupon.event.CouponIssueRequested;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLT 격리 기록의 원문(이벤트 JSON) 읽기·쓰기. 재처리·영구 거절이 원문에서 이벤트를 다시 읽는다.
 * 역직렬화에 실패해 격리된 메시지는 받은 바이트 그대로라 읽지 못한다.
 * <ul>
 *     <li>발급 요청: {@link CouponIssueRequested}.</li>
 *     <li>주문 결제·취소 완료: {@link OrderItemPaidEvent}·{@link OrderItemCancelCompletedEvent}. 원문만으로 어느 이벤트인지 가르려고
 *     결정적 eventId의 접두어({@code order-item-paid:}·{@code order-item-cancel-completed:})를 본다.</li>
 * </ul>
 */
public final class CouponDltPayload {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CouponDltPayload() {
    }

    public static String write(CouponIssueRequested event) {
        return JSON.writeValueAsString(event);
    }

    /** 원문을 발급 요청 이벤트로 읽는다. 읽지 못하면 빈 값 */
    public static Optional<CouponIssueRequested> read(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JSON.readValue(payload, CouponIssueRequested.class));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    /** 주문 결제·취소 완료 이벤트를 원문으로 쓴다 */
    public static String writeOrderEvent(Object event) {
        if (!(event instanceof OrderItemPaidEvent) && !(event instanceof OrderItemCancelCompletedEvent)) {
            throw new IllegalArgumentException("주문 결제·취소 완료 이벤트가 아닙니다: " + event.getClass().getName());
        }
        return JSON.writeValueAsString(event);
    }

    /**
     * 원문을 주문 결제·취소 완료 이벤트로 읽는다. eventId 접두어로 타입을 정한다.
     *
     * @return {@link OrderItemPaidEvent} 또는 {@link OrderItemCancelCompletedEvent}. 읽지 못하면 빈 값
     */
    public static Optional<Object> readOrderEvent(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode eventId = JSON.readTree(payload).get("eventId");
            String value = eventId == null || !eventId.isString() ? "" : eventId.asString();
            if (value.startsWith(OrderItemPaidEvent.EVENT_ID_PREFIX)) {
                return Optional.ofNullable(JSON.readValue(payload, OrderItemPaidEvent.class));
            }
            if (value.startsWith(OrderItemCancelCompletedEvent.EVENT_ID_PREFIX)) {
                return Optional.ofNullable(JSON.readValue(payload, OrderItemCancelCompletedEvent.class));
            }
            return Optional.empty();
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }
}
