package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;

/**
 * 호스트가 출연 취소 요청을 수락해 출연이 합의 취소됐다 (위약금 없음). 요청 하나에 많아야 한 번 발행되고, 같은 요청에 거절 이벤트와 함께 나가지 않는다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance:<출연 ID>}로 나간다 (#817). 최소 한 번 전달이라 받는 쪽은
 * {@code cancelRequestId}로 중복을 거른다.
 *
 * @param cancelRequest 취소 요청 정보
 * @param acceptedAt    호스트가 수락한 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance:' + #this.cancelRequest().appearanceId()}")
public record AppearanceCancelRequestAcceptedEvent(AppearanceCancelRequestDto cancelRequest, LocalDateTime acceptedAt) {
}
