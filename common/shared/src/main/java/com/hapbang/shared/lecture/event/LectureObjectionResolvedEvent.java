package com.hapbang.shared.lecture.event;

import org.springframework.modulith.events.Externalized;

/**
 * 강의가 이의 제기 판단을 반영했다 (#278, D71). 인정이면 항목이 반환 처리 중이 되고, HP 반환은 아직 끝나지 않았다.
 * 반환이 끝나면 {@link LectureObjectionReturnCompletedEvent}가 따로 나간다. 판단 명령 하나에 한 번 발행된다.
 *
 * @param commandId   처리한 판단 명령 ID
 * @param inquiryId   이의 제기 문의 ID
 * @param orderItemId 주문 항목 ID
 * @param accepted    인정이면 true, 기각이면 false
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureObjectionResolvedEvent(String commandId, Long inquiryId, Long orderItemId, boolean accepted) {
}
