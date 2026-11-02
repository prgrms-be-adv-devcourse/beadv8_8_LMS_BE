package com.hapbang.shared.lecture.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;

/**
 * 강의가 이의 제기 판단·철회 명령을 거절했다 (#278, D60 (3)·D71). 항목은 바뀌지 않는다.
 * 같은 명령을 다시 보내도 같은 거절이므로, 고객센터는 원인을 고친 뒤 새 명령 ID로 복구한다(#267).
 *
 * @param commandId   거절한 명령 ID
 * @param inquiryId   이의 제기 문의 ID
 * @param orderItemId 주문 항목 ID
 * @param commandType 거절한 명령 종류 ({@code RESOLVE} 또는 {@code WITHDRAW})
 * @param failureCode 거절 사유
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureObjectionRequestRefusedEvent(String commandId, Long inquiryId, Long orderItemId,
                                                  LectureObjectionCommandType commandType,
                                                  LectureObjectionFailureCode failureCode) {
}
