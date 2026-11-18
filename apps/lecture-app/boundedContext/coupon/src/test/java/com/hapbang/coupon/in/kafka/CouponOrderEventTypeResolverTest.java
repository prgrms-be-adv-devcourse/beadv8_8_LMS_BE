package com.hapbang.coupon.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

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

    /**
     * 리스너 설정의 {@code spring.json.value.type.method}가 가리키는 메서드가 있고, spring-kafka 역직렬화기가 먼저 찾는 모양
     * ({@code public static JavaType 이름(String topic, byte[] data, Headers headers)})인지 본다. 이름이 틀리면 리스너 기동이나 첫 메시지에서야
     * 실패한다. 값은 운영 리스너의 애노테이션에서 읽는다
     */
    @Test
    void 리스너_설정의_타입_결정_메서드는_역직렬화기가_찾는_public_static_메서드다() throws Exception {
        String methodProperty = listenerProperties().get(JacksonJsonDeserializer.VALUE_TYPE_METHOD);
        int lastDot = methodProperty.lastIndexOf('.');
        Class<?> owner = Class.forName(methodProperty.substring(0, lastDot));
        Method method = owner.getDeclaredMethod(methodProperty.substring(lastDot + 1), String.class, byte[].class,
                Headers.class);

        assertThat(Modifier.isPublic(owner.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        assertThat(method.getReturnType()).isEqualTo(JavaType.class);
    }

    /** 같은 설정으로 만든 역직렬화기가 그 메서드로 타입을 정한다 */
    @Test
    void 리스너_설정으로_만든_역직렬화기는_eventType_헤더로_타입을_정한다() {
        try (JacksonJsonDeserializer<Object> deserializer = new JacksonJsonDeserializer<>()) {
            deserializer.configure(listenerProperties(), false);

            assertThat(deserializer.deserialize(LectureEventTopic.NAME, eventType("LectureObjectionResolvedEvent"), BODY))
                    .isInstanceOf(IgnoredLectureEvent.class);
        }
    }

    /** 운영 리스너 애노테이션의 Kafka 소비 설정({@code key=value}) */
    private static Map<String, String> listenerProperties() {
        return Arrays.stream(CouponOrderEventListener.class.getAnnotation(KafkaListener.class).properties())
                .map(property -> property.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(pair -> pair[0].trim(), pair -> pair[1].trim()));
    }

    private static RecordHeaders eventType(String value) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(CouponOrderEventTypeResolver.EVENT_TYPE_HEADER, value.getBytes(StandardCharsets.UTF_8));
        return headers;
    }
}
