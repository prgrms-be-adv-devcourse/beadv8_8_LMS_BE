package com.hapbang.shared.lecture.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;

/**
 * 구매(주문 항목)가 바뀌었다 (#712, D82). 구매 결과나 실린 값이 바뀌는 전이에서만 전체 상태를 싣는다
 * (주문, 결제 성공, 결제 실패, 구매 취소 요청·완료, 반환 처리 중·반환 완료). 구매 확정·지급처럼 결과가 그대로인 전이는 보내지 않는다.
 * key는 이의 제기 이벤트와 같은 {@code order-item:<주문 항목 ID>}다.
 *
 * @param purchase 바뀐 뒤의 구매
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.purchase().orderItemId()}")
public record LecturePurchaseChangedEvent(LecturePurchaseSyncDto purchase) {
}
