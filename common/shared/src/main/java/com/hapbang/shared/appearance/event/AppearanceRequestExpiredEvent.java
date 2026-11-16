package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

/**
 * 수락 기한까지 게스트가 응답하지 않아 출연 요청이 만료됐다 (#871). 보류 HP는 호스트에게 반환된다. 요청서 하나에 많아야 한 번
 * 발행되고, 같은 요청서에 수락·거절·취소 이벤트와 함께 나가지 않는다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-request:<요청서 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code (eventType, requestId)}로 중복을 거른다(같은 대상의 다른 이벤트와 구분한다).
 *
 * @param request   만료된 요청서
 * @param expiredAt 만료 배치가 처리한 시각 (KST). 수락 기한보다 늦다
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-request:' + #this.request().requestId()}")
public record AppearanceRequestExpiredEvent(
        AppearanceRequestDto request,
        LocalDateTime expiredAt
) {
}
