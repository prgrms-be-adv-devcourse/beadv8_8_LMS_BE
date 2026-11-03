package com.hapbang.member.app;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.member.event.UserModifiedEvent;

/**
 * Kafka로 내보내는 회원 이벤트인지 판단한다 (#421). 재발행 배치가 쓴다.
 * <ul>
 *     <li>shared 회원 이벤트({@code com.hapbang.shared.member.event}) 중 {@link Externalized}가 붙은 레코드
 *     (가입·변경·로그아웃)만 내보낸다. 토픽·key도 그 애노테이션이 정한다.</li>
 *     <li>다른 컨텍스트의 shared 이벤트(출연 리뷰 공개 등)에도 {@link Externalized}가 붙지만, 회원 앱은 그것을 받기만 하므로
 *     다시 보내지 않는다.</li>
 * </ul>
 */
public final class MemberExternalEvents {

    private static final String PACKAGE = UserModifiedEvent.class.getPackageName();

    private MemberExternalEvents() {
    }

    public static boolean isExternalized(Object event) {
        return isExternalizedType(event.getClass());
    }

    static boolean isExternalizedType(Class<?> type) {
        return type.getPackageName().equals(PACKAGE) && type.isAnnotationPresent(Externalized.class);
    }
}
