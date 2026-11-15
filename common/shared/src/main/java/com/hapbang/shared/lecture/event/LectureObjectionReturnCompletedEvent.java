package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;

import org.springframework.modulith.events.Externalized;

/**
 * 이의 제기 인정으로 보류 HP를 구매자에게 반환했다 (#278, D60 (5)·D71). 항목이 반환이 되었다. 반환 작업 하나에 한 번 발행된다.
 * 판단 결과({@link LectureObjectionResolvedEvent})보다 먼저 도착할 수 있다.
 * <p>
 * 받는 사람·제목·완료 시각은 알림용이다(#818, D83). 기존 필드 뒤에 붙였고, #818 배포 전에 저장된 발행 기록을 다시 보낸 것에는 없어
 * {@code null}이다. 그래서 레코드에서 {@code null}을 막지 않는다.
 *
 * @param inquiryId    이의 제기 문의 ID
 * @param orderItemId  주문 항목 ID
 * @param buyerUserId  구매자 회원 ID
 * @param sellerUserId 판매자 회원 ID
 * @param lectureTitle 구매할 때의 강의 제목. 구매 뒤 강의 제목이 바뀌어도 그대로다
 * @param returnedAt   반환 완료 시각(KST offset)
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureObjectionReturnCompletedEvent(Long inquiryId, Long orderItemId, Long buyerUserId,
                                                   Long sellerUserId, String lectureTitle,
                                                   OffsetDateTime returnedAt) {

    public LectureObjectionReturnCompletedEvent {
        returnedAt = LectureEventTimes.kst(returnedAt);
    }
}
