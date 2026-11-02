package com.hapbang.shared.lecture.event;

import org.springframework.modulith.events.Externalized;

/**
 * 이의 제기 인정으로 보류 HP를 구매자에게 반환했다 (#278, D60 (5)·D71). 항목이 반환이 되었다. 반환 작업 하나에 한 번 발행된다.
 * 판단 결과({@link LectureObjectionResolvedEvent})보다 먼저 도착할 수 있다.
 *
 * @param inquiryId   이의 제기 문의 ID
 * @param orderItemId 주문 항목 ID
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureObjectionReturnCompletedEvent(Long inquiryId, Long orderItemId) {
}
