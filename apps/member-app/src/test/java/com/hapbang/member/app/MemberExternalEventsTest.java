package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserLoggedOutEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

/**
 * Kafka로 내보내는 회원 이벤트 판단 (#421). shared 회원 이벤트 중 {@code @Externalized}가 붙은 것만 다시 보낸다.
 */
class MemberExternalEventsTest {

    @Test
    @DisplayName("회원 가입·변경·로그아웃 이벤트는 내보내는 이벤트다")
    void externalized() {
        assertThat(MemberExternalEvents.isExternalizedType(UserJoinedEvent.class)).isTrue();
        assertThat(MemberExternalEvents.isExternalizedType(UserModifiedEvent.class)).isTrue();
        assertThat(MemberExternalEvents.isExternalizedType(UserLoggedOutEvent.class)).isTrue();
    }

    @Test
    @DisplayName("회원 앱이 받기만 하는 다른 컨텍스트 이벤트(출연 리뷰 공개)는 @Externalized가 붙어 있어도 내보내지 않는다 (패키지로 거름)")
    void otherContextEventsNotExternalized() {
        // 애노테이션만으로는 걸러지지 않는 경우여야 패키지 조건을 확인한다
        assertThat(AppearanceReviewPublishedEvent.class.isAnnotationPresent(Externalized.class)).isTrue();
        assertThat(MemberExternalEvents.isExternalizedType(AppearanceReviewPublishedEvent.class)).isFalse();
    }
}
