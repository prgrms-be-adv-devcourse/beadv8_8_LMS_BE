package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.domain.AppearanceRecruitCloseType;
import com.hapbang.shared.appearance.dto.AppearanceRecruitDto;

/**
 * 모집이 마감됐다 (#871). 마감 일시가 지났거나(기한 마감) 호스트가 조기 마감했다. 모집글 하나에 많아야 한 번 발행된다
 * (마감한 모집글을 나중에 모집 전체 취소하면 모집 취소 이벤트가 따로 나간다).
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-recruit:<모집글 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code (eventType, recruitId)}로 중복을 거른다(같은 대상의 다른 이벤트와 구분한다).
 *
 * @param recruit   마감된 모집글
 * @param closeType 마감 방식 (기한·조기)
 * @param closedAt  마감한 시각 (KST). 기한 마감이면 배치가 처리한 시각이라 마감 일시보다 늦다
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-recruit:' + #this.recruit().recruitId()}")
public record AppearanceRecruitClosedEvent(
        AppearanceRecruitDto recruit,
        AppearanceRecruitCloseType closeType,
        LocalDateTime closedAt
) {
}
