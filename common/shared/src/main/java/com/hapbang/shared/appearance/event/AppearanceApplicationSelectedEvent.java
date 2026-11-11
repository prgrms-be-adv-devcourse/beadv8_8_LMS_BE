package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;

/**
 * 호스트가 모집 지원을 수락해 출연이 성립했다 (#871). 지원 하나에 많아야 한 번 발행되고, 같은 지원에 미선정 이벤트와 함께 나가지 않는다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-application:<지원 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code (eventType, applicationId)}로 중복을 거른다(같은 대상의 다른 이벤트와 구분한다).
 *
 * @param application  수락된 지원 (모집글 제목·호스트 포함)
 * @param appearanceId 성립한 출연 ID
 * @param scheduledAt  방송 예정 일시 (KST)
 * @param selectedAt   호스트가 수락한 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-application:' + #this.application().applicationId()}")
public record AppearanceApplicationSelectedEvent(
        AppearanceApplicationDto application,
        Long appearanceId,
        LocalDateTime scheduledAt,
        LocalDateTime selectedAt
) {
}
