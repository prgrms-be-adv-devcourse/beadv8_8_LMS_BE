package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

/**
 * 게스트가 출연 요청을 수락해 출연이 성립했다 (#871). 요청서 하나에 많아야 한 번 발행되고, 같은 요청서에 거절·취소·만료 이벤트와
 * 함께 나가지 않는다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-request:<요청서 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code (eventType, requestId)}로 중복을 거른다(같은 대상의 다른 이벤트와 구분한다).
 *
 * @param request      수락한 요청서
 * @param appearanceId 성립한 출연 ID
 * @param acceptedAt   게스트가 수락한 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-request:' + #this.request().requestId()}")
public record AppearanceRequestAcceptedEvent(
        AppearanceRequestDto request,
        Long appearanceId,
        LocalDateTime acceptedAt
) {
}
