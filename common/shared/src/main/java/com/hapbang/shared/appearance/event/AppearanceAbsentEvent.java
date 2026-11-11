package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceDto;

/**
 * 호스트가 게스트의 불참을 처리했다 (#871). 출연료는 지급되지 않고 호스트에게 반환된다. 출연 하나에 많아야 한 번 발행되고,
 * 같은 출연에 확인·호스트 일방 취소 이벤트와 함께 나가지 않는다. 불참 사유는 담지 않는다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance:<출연 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code appearanceId}로 중복을 거른다.
 *
 * @param appearance 불참 처리된 출연
 * @param absentAt   호스트가 불참을 처리한 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance:' + #this.appearance().appearanceId()}")
public record AppearanceAbsentEvent(
        AppearanceDto appearance,
        LocalDateTime absentAt
) {
}
