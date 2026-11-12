package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;

/**
 * 모집 지원이 미선정됐다 (#871). 호스트가 거절했거나, 응답 전에 모집이 마감(기한·조기)되거나 모집 전체가 취소됐다.
 * 지원 하나에 많아야 한 번 발행되고, 같은 지원에 수락 이벤트와 함께 나가지 않는다. 거절 사유는 없다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-application:<지원 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code (eventType, applicationId)}로 중복을 거른다(같은 대상의 다른 이벤트와 구분한다).
 *
 * @param application   미선정된 지원 (모집글 제목·호스트 포함)
 * @param scheduledAt   방송 예정 일시 (KST)
 * @param notSelectedAt 거절·마감·모집 취소를 처리한 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-application:' + #this.application().applicationId()}")
public record AppearanceApplicationNotSelectedEvent(
        AppearanceApplicationDto application,
        LocalDateTime scheduledAt,
        LocalDateTime notSelectedAt
) {
}
