package com.hapbang.coupon.in.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaHandler;

import com.hapbang.shared.lecture.event.LectureEventTopic;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

import tools.jackson.databind.JavaType;

/** 강의 이벤트를 어떤 타입으로 읽을지 {@code eventType} 헤더로 정한다. 쿠폰은 결제 완료·취소 완료만 읽고 나머지는 무시한다 */
class CouponOrderEventTypeResolverTest {

    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    @Test
    void 결제_완료_eventType은_그_이벤트_클래스로_읽는다() {
        JavaType type = CouponOrderEventTypeResolver.resolve(LectureEventTopic.NAME, BODY,
                eventType(OrderItemPaidEvent.EVENT_TYPE));

        assertThat(type.getRawClass()).isEqualTo(OrderItemPaidEvent.class);
    }

    @Test
    void 쿠폰이_받지_않는_eventType은_무시용_레코드로_읽는다() {
        JavaType type = CouponOrderEventTypeResolver.resolve(LectureEventTopic.NAME, BODY,
                eventType("LectureObjectionResolvedEvent"));

        assertThat(type.getRawClass()).isEqualTo(IgnoredLectureEvent.class);
    }

    /** 강의 앱은 늘 헤더를 붙인다. 헤더가 빠진 메시지를 조용히 버리지 않고 {@code __TypeId__}로 찾게 null을 돌려준다 */
    @Test
    void eventType_헤더가_없으면_null을_돌려_TypeId로_찾게_한다() {
        assertThat(CouponOrderEventTypeResolver.resolve(LectureEventTopic.NAME, BODY, new RecordHeaders())).isNull();
    }

    /** 처리기를 더하고 표에 빠뜨리면 그 이벤트를 무시용 레코드로 읽어 조용히 버린다. 처리기마다 표에 있는지 본다 */
    @Test
    void 받는_타입마다_eventType_표에_있다() {
        List<Class<?>> handled = Arrays.stream(CouponOrderEventListener.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(KafkaHandler.class)
                        && !method.getAnnotation(KafkaHandler.class).isDefault())
                .map(method -> method.getParameterTypes()[0])
                .toList();

        assertThat(handled).hasSize(2).allSatisfy(type -> assertThat(CouponOrderEventTypeResolver
                .resolve(LectureEventTopic.NAME, BODY, eventType(type.getSimpleName())).getRawClass())
                .isEqualTo(type));
    }

    private static RecordHeaders eventType(String value) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(CouponOrderEventTypeResolver.EVENT_TYPE_HEADER, value.getBytes(StandardCharsets.UTF_8));
        return headers;
    }
}
