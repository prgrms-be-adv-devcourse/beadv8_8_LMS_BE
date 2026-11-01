package com.hapbang.appearance.app;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.event.AppearanceEventTopic;

/**
 * Kafka로 내보내는 출연 이벤트인지 판단한다 (#393). 외부화 설정과 재발행 배치가 같이 쓴다.
 * <ul>
 *     <li>shared 출연 이벤트 중 {@link Externalized}가 붙은 레코드만 내보낸다. 토픽·key도 그 애노테이션이 정한다.
 *     지금은 요청 도착·지원 도착 두 가지이고, 출연 취소 요청 이벤트 3종은 받는 곳이 없어 붙이지 않았다.</li>
 *     <li>다른 컨텍스트의 shared 이벤트(회원 이벤트 등)에도 {@link Externalized}가 붙지만, 출연 앱은 그것을 받기만 하므로 내보내지 않는다.</li>
 * </ul>
 */
public final class AppearanceExternalEvents {

    private static final String PACKAGE = AppearanceEventTopic.class.getPackageName();

    private AppearanceExternalEvents() {
    }

    public static boolean isExternalized(Object event) {
        return isExternalizedType(event.getClass());
    }

    static boolean isExternalizedType(Class<?> type) {
        return type.getPackageName().equals(PACKAGE) && type.isAnnotationPresent(Externalized.class);
    }
}
