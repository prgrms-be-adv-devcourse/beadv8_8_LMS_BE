package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceDto;

/**
 * 호스트가 성립한 출연을 일방 취소했다 (#871). 위약금은 게스트에게 지급되고 나머지는 호스트에게 반환된다. 출연 하나에 많아야 한 번
 * 발행된다. 모집 전체 취소도 성립한 출연마다 이 이벤트를 낸다. 취소 사유는 담지 않는다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance:<출연 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code (eventType, appearanceId)}로 중복을 거른다(같은 대상의 다른 이벤트와 구분한다).
 *
 * @param appearance    취소된 출연
 * @param penaltyAmount 게스트에게 지급하는 위약금 (HP)
 * @param canceledAt    호스트가 취소한 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance:' + #this.appearance().appearanceId()}")
public record AppearanceCanceledByHostEvent(
        AppearanceDto appearance,
        long penaltyAmount,
        LocalDateTime canceledAt
) {
}
