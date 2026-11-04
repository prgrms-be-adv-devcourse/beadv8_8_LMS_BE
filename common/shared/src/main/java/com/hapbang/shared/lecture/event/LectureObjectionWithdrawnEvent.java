package com.hapbang.shared.lecture.event;

import org.springframework.modulith.events.Externalized;

/**
 * 강의가 이의 제기 철회를 반영했다 (#278, D52·D71). 항목은 접수 전 상태로 돌아가 지급·구매 취소가 다시 가능하다.
 * 철회 명령 하나에 한 번 발행된다.
 *
 * @param commandId   처리한 철회 명령 ID
 * @param inquiryId   이의 제기 문의 ID
 * @param orderItemId 주문 항목 ID
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureObjectionWithdrawnEvent(String commandId, Long inquiryId, Long orderItemId) {
}
