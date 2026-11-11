package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;

/**
 * 출연 취소 요청이 거절돼 출연이 유지된다. 요청 하나에 많아야 한 번 발행되고, 같은 요청에 수락 이벤트와 함께 나가지 않는다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance:<출연 ID>}로 나간다 (#817). 최소 한 번 전달이라 받는 쪽은
 * {@code cancelRequestId}로 중복을 거른다.
 *
 * @param cancelRequest 취소 요청 정보
 * @param autoRejected  true면 방송 예정 일시까지 호스트가 응답하지 않아 자동으로 거절됐다
 * @param rejectedAt    거절한 시각 (KST). 자동 거절이면 자동 거절을 처리한 시각
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance:' + #this.cancelRequest().appearanceId()}")
public record AppearanceCancelRequestRejectedEvent(AppearanceCancelRequestDto cancelRequest, boolean autoRejected,
        LocalDateTime rejectedAt) {
}
