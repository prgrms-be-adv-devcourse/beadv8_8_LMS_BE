package com.hapbang.coupon.in.event;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

/**
 * 강의 이벤트({@code lecture.events.v1})를 어떤 타입으로 읽을지 {@code eventType} 헤더로 정한다(kafka.md "소비").
 * <ul>
 *     <li>결제 완료·취소 완료는 그 레코드로 읽는다. 헤더 값은 강의 앱이 보내는 값({@link OrderItemPaidEvent#EVENT_TYPE}·
 *     {@link OrderItemCancelCompletedEvent#EVENT_TYPE})이다. 처리기({@link CouponOrderEventListener})를 더하면 표에도 더한다.</li>
 *     <li>그 밖의 강의 이벤트는 본문을 읽지 않고 {@link IgnoredLectureEvent}로 읽는다. 쿠폰이 쓰지 않는 이벤트의 형식 문제로 이 소비자가
 *     DLT를 쌓지 않게 한다.</li>
 *     <li>헤더가 없거나 값이 null이면 null을 돌려 {@code __TypeId__}로 찾게 한다. 받는 이벤트를 조용히 버리지 않기 위해서다.</li>
 * </ul>
 */
public final class CouponOrderEventTypeResolver {

    /** 강의 앱이 붙이는 이벤트 타입 헤더 이름(kafka.md "발행") */
    static final String EVENT_TYPE_HEADER = "eventType";

    private static final TypeFactory TYPE_FACTORY = TypeFactory.createDefaultInstance();

    private static final Map<String, JavaType> HANDLED = Map.of(
            OrderItemPaidEvent.EVENT_TYPE, TYPE_FACTORY.constructType(OrderItemPaidEvent.class),
            OrderItemCancelCompletedEvent.EVENT_TYPE, TYPE_FACTORY.constructType(OrderItemCancelCompletedEvent.class));

    private static final JavaType IGNORED = TYPE_FACTORY.constructType(IgnoredLectureEvent.class);

    private CouponOrderEventTypeResolver() {
    }

    /** {@code JacksonJsonTypeResolver#resolveType}과 같은 모양이다. 역직렬화기가 이 메서드를 이름으로 찾는다 */
    public static JavaType resolve(String topic, byte[] data, Headers headers) {
        Header header = headers.lastHeader(EVENT_TYPE_HEADER);
        if (header == null || header.value() == null) {
            return null;
        }
        return HANDLED.getOrDefault(new String(header.value(), StandardCharsets.UTF_8), IGNORED);
    }
}
