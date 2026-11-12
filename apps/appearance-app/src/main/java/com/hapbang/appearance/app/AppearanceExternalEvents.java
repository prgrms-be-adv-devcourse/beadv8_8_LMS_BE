package com.hapbang.appearance.app;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.event.AppearanceEventTopic;

/**
 * Kafka로 내보내는 출연 이벤트인지 판단한다 (#393). 외부화 설정과 재발행 배치가 같이 쓴다.
 * <ul>
 *     <li>shared 출연 이벤트 중 {@link Externalized}가 붙은 레코드만 내보낸다. 토픽·key도 그 애노테이션이 정한다.
 *     지금은 요청 도착·지원 도착·리뷰 공개(#513)·출연 변경(#713)·출연 취소 요청 3종(요청·수락·거절, #817)과
 *     알림용 12종(요청서 수락·거절·취소·만료, 지원 선정·미선정, 모집 시작·마감·취소, 호스트 일방 취소·확인·불참, #871)이다.</li>
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
