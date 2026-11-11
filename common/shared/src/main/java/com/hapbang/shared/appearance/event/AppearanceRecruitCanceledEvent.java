package com.hapbang.shared.appearance.event;

import java.time.LocalDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceRecruitDto;

/**
 * 호스트가 모집 전체를 취소했다 (#871). 모집 중이든 마감 뒤든 모집글 하나에 많아야 한 번 발행된다. 성립한 출연마다 호스트 일방 취소
 * 이벤트, 대기 중인 지원마다 미선정 이벤트가 같은 처리에서 따로 나간다. 취소 사유는 담지 않는다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance-recruit:<모집글 ID>}로 나간다. 최소 한 번 전달이라
 * 받는 쪽은 {@code (eventType, recruitId)}로 중복을 거른다(같은 대상의 다른 이벤트와 구분한다).
 *
 * @param recruit    취소된 모집글
 * @param canceledAt 호스트가 취소한 시각 (KST)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance-recruit:' + #this.recruit().recruitId()}")
public record AppearanceRecruitCanceledEvent(
        AppearanceRecruitDto recruit,
        LocalDateTime canceledAt
) {
}
