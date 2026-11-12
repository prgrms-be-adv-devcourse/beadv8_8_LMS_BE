package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.lecture.dto.LectureOrderItemDto;

/**
 * 주문이 결제 실패로 끝났다 (#872, D85). HP 보류가 거절됐거나 보류 무효화로 끝났거나 쿠폰 예약이 거절돼, 묶여 남은 HP가 없다.
 * 주문마다 한 번 발행된다. key는 {@code order:<주문 ID>}다.
 *
 * @param orderId     주문 ID
 * @param buyerUserId 구매자 회원 ID
 * @param items       주문의 모든 항목
 * @param failedAt    결제 실패를 기록한 시각(KST offset)
 */
@Externalized(LectureEventTopic.NAME + "::#{'order:' + #this.orderId()}")
public record LectureOrderHoldFailedEvent(Long orderId, Long buyerUserId, List<LectureOrderItemDto> items,
                                          OffsetDateTime failedAt) {

    public LectureOrderHoldFailedEvent {
        items = items == null ? null : List.copyOf(items);
        failedAt = LectureEventTimes.kst(failedAt);
    }
}
