package com.hapbang.customersupport.in;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import com.hapbang.customersupport.global.config.CustomerSupportEventExternalizationConfig;
import com.hapbang.shared.lecture.event.LectureObjectionRequestRefusedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

/**
 * 강의 이벤트({@code lecture.events.v1})를 어떤 타입으로 읽을지 {@code eventType} 헤더로 정한다 (#722, kafka.md "소비").
 * <ul>
 *     <li>받는 타입은 아래 표의 클래스로 읽는다. 헤더 값은 강의 앱이 보내는 레코드 이름({@code LectureEventExternalizationConfig})이고,
 *     강의 앱은 레코드 이름을 바꿔도 이 값을 그대로 둔다. 처리기({@link CustomerSupportLectureEventListener})를 더하면 표에도 더한다.</li>
 *     <li>받지 않는 타입은 클래스를 찾지 않고 {@link IgnoredLectureEvent}로 읽는다. {@code __TypeId__}로 찾으면 native 이미지에
 *     등록하지 않은 클래스라 역직렬화에 실패해 DLT로 간다.</li>
 *     <li>헤더가 없으면 null을 돌려 지금처럼 {@code __TypeId__}로 찾게 한다. 받는 이벤트를 조용히 버리지 않기 위해서다.</li>
 * </ul>
 * 역직렬화기가 {@code spring.json.value.type.method}로 이름을 받아 reflection으로 부르므로, native 이미지에는
 * {@code reachability-metadata.json}에 이 메서드를 등록한다.
 */
public final class LectureEventTypeResolver {

    private static final TypeFactory TYPE_FACTORY = TypeFactory.createDefaultInstance();

    private static final Map<String, JavaType> HANDLED = Map.of(
            "LectureObjectionResolvedEvent", type(LectureObjectionResolvedEvent.class),
            "LectureObjectionWithdrawnEvent", type(LectureObjectionWithdrawnEvent.class),
            "LectureObjectionRequestRefusedEvent", type(LectureObjectionRequestRefusedEvent.class),
            "LectureObjectionReturnCompletedEvent", type(LectureObjectionReturnCompletedEvent.class),
            "LectureObjectionReturnActionRequiredEvent", type(LectureObjectionReturnActionRequiredEvent.class));

    private static final JavaType IGNORED = type(IgnoredLectureEvent.class);

    private LectureEventTypeResolver() {
    }

    /** {@code JacksonJsonTypeResolver#resolveType}과 같은 모양이다. 역직렬화기가 이 메서드를 이름으로 찾는다 */
    public static JavaType resolve(String topic, byte[] data, Headers headers) {
        Header header = headers.lastHeader(CustomerSupportEventExternalizationConfig.EVENT_TYPE);
        if (header == null) {
            return null;
        }
        return HANDLED.getOrDefault(new String(header.value(), StandardCharsets.UTF_8), IGNORED);
    }

    private static JavaType type(Class<?> type) {
        return TYPE_FACTORY.constructType(type);
    }
}
