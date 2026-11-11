package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

/**
 * 게스트가 응답하기 전에 호스트가 출연 요청을 취소했다 (#871). 보류 HP는 호스트에게 반환된다. 요청서 하나에 많아야 한 번 발행되고,
 * 같은 요청서에 수락·거절·만료 이벤트와 함께 나가지 않는다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-request:<요청서 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code requestId}로 중복을 거른다.
 *
 * @param request    취소된 요청서
 * @param canceledAt 호스트가 취소한 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-request:' + #this.request().requestId()}")
public record AppearanceRequestCanceledEvent(
        AppearanceRequestDto request,
        LocalDateTime canceledAt
) {
}
