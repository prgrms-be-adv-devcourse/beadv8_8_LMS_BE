package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;

/**
 * 게스트가 출연 취소를 요청했다 (호스트 응답을 기다림). 요청 하나에 한 번 발행된다. 호스트에게 취소 요청 도착을 알리는 데 쓴다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance:<출연 ID>}로 나간다 (#817). 출연 변경 이벤트와 key가 같아
 * 같은 출연의 이벤트는 같은 partition에 모인다. 최소 한 번 전달이라 받는 쪽은 {@code cancelRequestId}로 중복을 거른다.
 *
 * @param cancelRequest 취소 요청 정보
 * @param requestedAt   취소를 요청한 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance:' + #this.cancelRequest().appearanceId()}")
public record AppearanceCancelRequestedEvent(AppearanceCancelRequestDto cancelRequest, LocalDateTime requestedAt) {
}
