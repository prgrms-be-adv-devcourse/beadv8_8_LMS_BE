package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.domain.AppearanceConfirmType;
import com.hapbang.shared.appearance.dto.AppearanceDto;

/**
 * 출연이 확인됐다 (#871). 출연료는 게스트에게 지급된다(지급 결과는 지갑 이벤트가 따로 알린다). 출연 하나에 많아야 한 번 발행되고,
 * 같은 출연에 불참·호스트 일방 취소 이벤트와 함께 나가지 않는다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance:<출연 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code (eventType, appearanceId)}로 중복을 거른다(같은 대상의 다른 이벤트와 구분한다).
 *
 * @param appearance  확인된 출연
 * @param confirmType 확인 방식 (호스트 확인·자동 확인)
 * @param confirmedAt 확인한 시각 (KST). 자동 확인이면 배치가 처리한 시각
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance:' + #this.appearance().appearanceId()}")
public record AppearanceConfirmedEvent(
        AppearanceDto appearance,
        AppearanceConfirmType confirmType,
        LocalDateTime confirmedAt
) {
}
