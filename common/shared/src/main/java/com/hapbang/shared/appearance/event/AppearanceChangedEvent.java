package com.hapbang.shared.appearance.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.appearance.dto.AppearanceSyncDto;

/**
 * 출연이 생기거나 상태가 바뀌었다 (#713). 성립부터 확인·불참·취소까지 모든 상태 전이에서 바뀐 뒤 전체 상태로 한 번씩 발행된다
 * (공개 결과가 같은 전이 {@code ESTABLISHED ↔ CANCEL_REQUESTED} 포함). AI 추천의 읽기용 사본이 쓴다.
 * <p>
 * Kafka {@value AppearanceEventTopic#NAME} 토픽에 key {@code appearance:<출연 ID>}로 나간다. 같은 출연은 같은 partition에 모이지만
 * 커밋 뒤 비동기 전송·재발행 때문에 순서는 보장하지 않는다. 받는 쪽은 {@code version}이 큰 것만 반영한다.
 *
 * @param appearance 바뀐 뒤 출연 (동기화 내부 API와 같은 값)
 */
@Externalized(AppearanceEventTopic.NAME + "::#{'appearance:' + #this.appearance().id()}")
public record AppearanceChangedEvent(AppearanceSyncDto appearance) {
}
