package com.hapbang.lecture.app;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.lecture.event.LectureEventTopic;

/**
 * Kafka로 내보내는 강의 이벤트인지 판단한다 (#278). 외부화 설정과 재발행 배치가 같이 쓴다.
 * <ul>
 *     <li>shared 강의 이벤트({@code com.hapbang.shared.lecture.event}) 중 {@link Externalized}가 붙은 레코드만 내보낸다.
 *     토픽·key도 그 애노테이션이 정한다.</li>
 *     <li>다른 컨텍스트의 shared 이벤트(회원·고객센터 이벤트)에도 {@link Externalized}가 붙지만, 강의 앱은 그것을 받기만 하므로 내보내지 않는다.</li>
 * </ul>
 */
public final class LectureExternalEvents {

    private static final String PACKAGE = LectureEventTopic.class.getPackageName();

    private LectureExternalEvents() {
    }

    public static boolean isExternalized(Object event) {
        return isExternalizedType(event.getClass());
    }

    static boolean isExternalizedType(Class<?> type) {
        return type.getPackageName().equals(PACKAGE) && type.isAnnotationPresent(Externalized.class);
    }
}
