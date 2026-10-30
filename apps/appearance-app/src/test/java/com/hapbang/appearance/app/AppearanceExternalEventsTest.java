package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestRejectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

/**
 * Kafka로 내보내는 출연 이벤트 판단 (#393). shared 출연 이벤트 중 {@code @Externalized}가 붙은 것만 내보낸다.
 */
class AppearanceExternalEventsTest {

    @Test
    @DisplayName("요청 도착·지원 도착은 내보낸다")
    void externalized() {
        assertThat(AppearanceExternalEvents.isExternalizedType(AppearanceRequestSentEvent.class)).isTrue();
        assertThat(AppearanceExternalEvents.isExternalizedType(AppearanceApplicationSubmittedEvent.class)).isTrue();
    }

    @Test
    @DisplayName("출연 취소 요청 이벤트 3종은 받는 곳이 없어 내보내지 않는다")
    void cancelRequestEventsNotExternalized() {
        assertThat(AppearanceExternalEvents.isExternalizedType(AppearanceCancelRequestedEvent.class)).isFalse();
        assertThat(AppearanceExternalEvents.isExternalizedType(AppearanceCancelRequestAcceptedEvent.class)).isFalse();
        assertThat(AppearanceExternalEvents.isExternalizedType(AppearanceCancelRequestRejectedEvent.class)).isFalse();
    }

    @Test
    @DisplayName("다른 컨텍스트의 shared 이벤트(회원 이벤트)는 @Externalized가 붙어 있어도 출연 앱이 내보내지 않는다 (패키지로 거름)")
    void otherContextEventsNotExternalized() {
        // 애노테이션만으로는 걸러지지 않는 경우여야 패키지 조건을 확인한다
        assertThat(UserJoinedEvent.class.isAnnotationPresent(Externalized.class)).isTrue();
        assertThat(UserModifiedEvent.class.isAnnotationPresent(Externalized.class)).isTrue();
        assertThat(AppearanceExternalEvents.isExternalizedType(UserJoinedEvent.class)).isFalse();
        assertThat(AppearanceExternalEvents.isExternalizedType(UserModifiedEvent.class)).isFalse();
    }
}
