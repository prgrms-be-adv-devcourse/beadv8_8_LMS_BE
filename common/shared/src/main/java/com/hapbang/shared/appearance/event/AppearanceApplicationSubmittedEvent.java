package com.hapbang.shared.appearance.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;

/**
 * 게스트가 출연 모집글에 지원했다. 지원 하나에 한 번 발행된다.
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-application:<지원 ID>}로 나간다 (#393).
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-application:' + #this.application().applicationId()}")
public record AppearanceApplicationSubmittedEvent(AppearanceApplicationDto application) {
}
