package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;

import org.springframework.modulith.events.Externalized;

/**
 * 이의 제기 인정 반환의 자동 처리가 멈춰 관리자 확인이 필요하다 (#278, D57·D60 (5)·D71). 항목은 반환 처리 중에 남는다.
 * 관리자가 반환 작업을 다시 시도해 또 멈추면 다시 발행되고, 반환이 끝나면 {@link LectureObjectionReturnCompletedEvent}가 나간다.
 * <p>
 * 받는 사람·제목·반환 지연 회차·발생 시각은 알림용이다(#818, D83). 기존 필드 뒤에 붙였고, #818 배포 전에 저장된 발행 기록을 다시
 * 보낸 것에는 없어 {@code null}이다. 그래서 레코드에서 {@code null}을 막지 않는다.
 *
 * @param inquiryId           이의 제기 문의 ID
 * @param orderItemId         주문 항목 ID
 * @param buyerUserId         구매자 회원 ID
 * @param sellerUserId        판매자 회원 ID
 * @param lectureTitle        구매할 때의 강의 제목. 구매 뒤 강의 제목이 바뀌어도 그대로다
 * @param returnDelaySequence 반환 지연 회차. 같은 주문 항목(= 같은 문의)에서 반환이 멈출 때마다 1부터 1씩 늘어난다.
 *                            강의가 주문 항목에 저장한 값이라 같은 이벤트를 다시 보내도 같다
 * @param occurredAt          반환 작업이 멈춘 것을 처리한 시각(KST offset)
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureObjectionReturnActionRequiredEvent(Long inquiryId, Long orderItemId, Long buyerUserId,
                                                        Long sellerUserId, String lectureTitle,
                                                        Integer returnDelaySequence, OffsetDateTime occurredAt) {

    public LectureObjectionReturnActionRequiredEvent {
        occurredAt = LectureEventTimes.kst(occurredAt);
    }
}
