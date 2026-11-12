package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;

import org.springframework.modulith.events.Externalized;

/**
 * 구매 취소의 반환이 끝나 주문 항목이 구매 취소됐다 (#872, D85). 항목마다 한 번 발행된다. key는 {@code order-item:<주문 항목 ID>}다.
 * 알림은 (주문 항목 ID, 구매자 ID)로 구매 취소 마감 임박 예약을 취소한다. 이의 제기 인정 반환은
 * {@link LectureObjectionReturnCompletedEvent}로 따로 나간다.
 *
 * @param orderItemId   주문 항목 ID
 * @param buyerUserId   구매자 회원 ID
 * @param lectureTitle  구매할 때의 강의 제목
 * @param buyerAmountHp 구매자에게 돌려준 HP = 가격 − 쿠폰 할인. 0일 수 있다
 * @param canceledAt    구매 취소(반환 완료) 시각(KST offset)
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureOrderItemCanceledEvent(Long orderItemId, Long buyerUserId, String lectureTitle,
                                            Long buyerAmountHp, OffsetDateTime canceledAt) {

    public LectureOrderItemCanceledEvent {
        canceledAt = LectureEventTimes.kst(canceledAt);
    }
}
