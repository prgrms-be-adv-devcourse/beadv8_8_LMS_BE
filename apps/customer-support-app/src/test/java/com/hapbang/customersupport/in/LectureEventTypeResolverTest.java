package com.hapbang.customersupport.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaHandler;

import com.hapbang.shared.lecture.event.LectureEventTopic;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;

import tools.jackson.databind.JavaType;

/**
 * 강의 이벤트를 어떤 타입으로 읽을지 {@code eventType} 헤더로 정한다 (#722). 헤더 값은 강의 앱이 보내는 레코드 이름
 * ({@code LectureEventExternalizationConfig})이다.
 */
class LectureEventTypeResolverTest {

    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    @Test
    void 받는_eventType은_그_이벤트_클래스로_읽는다() {
        JavaType type = LectureEventTypeResolver.resolve(LectureEventTopic.NAME, BODY,
                eventType("LectureObjectionResolvedEvent"));

        assertThat(type.getRawClass()).isEqualTo(LectureObjectionResolvedEvent.class);
    }

    /** 고객센터가 받지 않는 타입은 클래스를 찾지 않는다. native 이미지에 없는 클래스여도 DLT로 가지 않는다 */
    @Test
    void 모르는_eventType은_무시용_레코드로_읽는다() {
        JavaType type = LectureEventTypeResolver.resolve(LectureEventTopic.NAME, BODY, eventType("LectureChangedEvent"));

        assertThat(type.getRawClass()).isEqualTo(IgnoredLectureEvent.class);
    }

    /** 강의 앱은 늘 헤더를 붙인다. 헤더가 빠진 메시지를 무시하지 않고, 지금처럼 {@code __TypeId__}로 찾게 null을 돌려준다 */
    @Test
    void eventType_헤더가_없으면_null을_돌려_TypeId로_찾게_한다() {
        JavaType type = LectureEventTypeResolver.resolve(LectureEventTopic.NAME, BODY, new RecordHeaders());

        assertThat(type).isNull();
    }

    /**
     * 처리기를 더하고 표에 빠뜨리면 그 이벤트를 무시용 레코드로 읽어 조용히 버린다. 처리기마다 표에 있는지 본다.
     * 헤더 값은 지금 레코드 이름과 같다. 강의 앱은 레코드 이름을 바꿔도 헤더 값을 그대로 두므로, 이름을 바꾸면 이 대응을 고친다
     */
    @Test
    void 받는_타입마다_eventType_표에_레코드_이름으로_있다() {
        List<Class<?>> handled = Arrays.stream(CustomerSupportLectureEventListener.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(KafkaHandler.class)
                        && !method.getAnnotation(KafkaHandler.class).isDefault())
                .map(method -> method.getParameterTypes()[0])
                .filter(type -> type != IgnoredLectureEvent.class)
                .toList();

        assertThat(handled).hasSize(5).allSatisfy(type -> assertThat(LectureEventTypeResolver
                .resolve(LectureEventTopic.NAME, BODY, eventType(type.getSimpleName())).getRawClass())
                .isEqualTo(type));
    }

    private static RecordHeaders eventType(String value) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("eventType", value.getBytes(StandardCharsets.UTF_8));
        return headers;
    }
}
