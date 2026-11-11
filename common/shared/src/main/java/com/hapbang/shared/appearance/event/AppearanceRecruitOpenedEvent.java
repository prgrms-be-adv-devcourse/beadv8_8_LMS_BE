package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceRecruitDto;

/**
 * 모집글의 HP 보류가 끝나 모집이 시작됐다 (#871). 모집글 하나에 한 번 발행된다(보류 결과를 올리기 요청과 재확인 배치가 함께 반영해도 한 번).
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-recruit:<모집글 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code recruitId}로 중복을 거른다.
 *
 * @param recruit  모집이 시작된 모집글
 * @param openedAt 모집 중이 된 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-recruit:' + #this.recruit().recruitId()}")
public record AppearanceRecruitOpenedEvent(
        AppearanceRecruitDto recruit,
        LocalDateTime openedAt
) {
}
