package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;

import org.springframework.modulith.events.Externalized;

/**
 * 강의가 이의 제기 판단을 반영했다 (#278, D71). 인정이면 항목이 반환 처리 중이 되고, HP 반환은 아직 끝나지 않았다.
 * 반환이 끝나면 {@link LectureObjectionReturnCompletedEvent}가 따로 나간다. 판단 명령 하나에 한 번 발행되고, 한 문의는 한 번만 판단된다.
 * <p>
 * 받는 사람·제목·판단 시각은 알림용이다(#818, D83). 기존 필드 뒤에 붙였고, #818 배포 전에 저장된 발행 기록을 다시 보낸 것에는 없어
 * {@code null}이다. 그래서 레코드에서 {@code null}을 막지 않는다.
 *
 * @param commandId    처리한 판단 명령 ID
 * @param inquiryId    이의 제기 문의 ID
 * @param orderItemId  주문 항목 ID
 * @param accepted     인정이면 true, 기각이면 false
 * @param buyerUserId  구매자 회원 ID
 * @param sellerUserId 판매자 회원 ID
 * @param lectureTitle 구매할 때의 강의 제목. 구매 뒤 강의 제목이 바뀌어도 그대로다
 * @param resolvedAt   판단을 반영한 시각(KST offset)
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureObjectionResolvedEvent(String commandId, Long inquiryId, Long orderItemId, boolean accepted,
                                            Long buyerUserId, Long sellerUserId, String lectureTitle,
                                            OffsetDateTime resolvedAt) {

    public LectureObjectionResolvedEvent {
        resolvedAt = LectureEventTimes.kst(resolvedAt);
    }
}
