package com.hapbang.appearance.app;

import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;

/**
 * Kafka로 내보내는 출연 이벤트인지 판단한다 (#393). 외부화 설정과 재발행 배치가 같이 쓴다.
 * 대상은 요청 도착·지원 도착 두 가지다. 출연 취소 요청 이벤트 3종은 받는 곳이 없어 같은 JVM 안에만 둔다.
 */
public final class AppearanceExternalEvents {

    private AppearanceExternalEvents() {
    }

    public static boolean isExternalized(Object event) {
        return event instanceof AppearanceRequestSentEvent || event instanceof AppearanceApplicationSubmittedEvent;
    }
}
