package com.hapbang.shared.appearance.event;

/**
 * 출연 이벤트 Kafka 토픽 (#339, #393). 출연 앱이 내보내는 이벤트 타입을 이 토픽 하나에 섞는다.
 * 토픽·key·헤더 규칙은 docs/architecture/infrastructure/kafka.md를 따른다.
 */
public final class AppearanceEventTopic {

    public static final String NAME = "appearance.events.v1";

    private AppearanceEventTopic() {
    }
}
