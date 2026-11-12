package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;

import org.springframework.modulith.events.Externalized;

/**
 * 강의가 내용 다름 이의 제기를 접수했다 (#872, D85, 알림 ND-015). 항목의 지급과 구매 취소가 멈춘다.
 * 고객센터의 바로 접수와 재시도 배치 접수 모두 같은 접수 명령으로 오고, 처음 처리한 접수 명령에서만 한 번 발행된다.
 * 거절된 접수와 같은 명령의 재전송은 발행하지 않는다. key는 이의 제기 결과 이벤트와 같은 {@code order-item:<주문 항목 ID>}다.
 *
 * @param inquiryId    이의 제기 문의 ID
 * @param orderItemId  주문 항목 ID
 * @param buyerUserId  구매자 회원 ID
 * @param sellerUserId 판매자 회원 ID
 * @param lectureTitle 구매할 때의 강의 제목
 * @param filedAt      접수 시각(KST offset)
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureObjectionFiledEvent(Long inquiryId, Long orderItemId, Long buyerUserId, Long sellerUserId,
                                         String lectureTitle, OffsetDateTime filedAt) {

    public LectureObjectionFiledEvent {
        filedAt = LectureEventTimes.kst(filedAt);
    }
}
