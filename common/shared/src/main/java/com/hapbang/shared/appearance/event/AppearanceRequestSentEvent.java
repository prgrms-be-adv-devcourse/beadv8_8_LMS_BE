package com.hapbang.shared.appearance.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

/**
 * 출연 요청서가 게스트에게 도착했다 (HP 보류가 끝나 응답을 기다리는 상태가 됨). 요청서 하나에 한 번 발행된다.
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-request:<요청서 ID>}로 나간다 (#393).
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-request:' + #this.request().requestId()}")
public record AppearanceRequestSentEvent(AppearanceRequestDto request) {
}
