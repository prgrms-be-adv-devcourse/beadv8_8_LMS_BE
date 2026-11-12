package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.lecture.dto.LectureOrderItemDto;

/**
 * 주문의 모든 항목 결제(HP 보류)가 끝나 구매가 완료됐다 (#872, D85). 주문마다 한 번 발행된다. key는 {@code order:<주문 ID>}다.
 * 알림은 항목마다 구매 취소 마감 임박 예약을 (주문 항목 ID, 구매자 ID)로 만든다. 예약을 취소하는
 * {@link LectureOrderItemCanceledEvent}·{@link LectureOrderItemConfirmedEvent}는 key가 달라 먼저 도착할 수 있다.
 *
 * @param orderId     주문 ID
 * @param buyerUserId 구매자 회원 ID
 * @param items       주문의 모든 항목
 * @param paidAt      구매(결제 완료) 시각(KST offset). 항목의 구매 시각과 같다
 */
@Externalized(LectureEventTopic.NAME + "::#{'order:' + #this.orderId()}")
public record LectureOrderPaidEvent(Long orderId, Long buyerUserId, List<LectureOrderItemDto> items,
                                    OffsetDateTime paidAt) {

    public LectureOrderPaidEvent {
        items = items == null ? null : List.copyOf(items);
        paidAt = LectureEventTimes.kst(paidAt);
    }
}
