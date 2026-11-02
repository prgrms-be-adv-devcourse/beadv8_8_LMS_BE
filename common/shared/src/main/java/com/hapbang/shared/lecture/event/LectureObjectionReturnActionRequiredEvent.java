package com.hapbang.shared.lecture.event;

import org.springframework.modulith.events.Externalized;

/**
 * 이의 제기 인정 반환의 자동 처리가 멈춰 관리자 확인이 필요하다 (#278, D57·D60 (5)·D71). 항목은 반환 처리 중에 남는다.
 * 관리자가 반환 작업을 다시 시도해 또 멈추면 다시 발행되고, 반환이 끝나면 {@link LectureObjectionReturnCompletedEvent}가 나간다.
 *
 * @param inquiryId   이의 제기 문의 ID
 * @param orderItemId 주문 항목 ID
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureObjectionReturnActionRequiredEvent(Long inquiryId, Long orderItemId) {
}
