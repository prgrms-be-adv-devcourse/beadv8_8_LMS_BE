package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;

import org.springframework.modulith.events.Externalized;

/**
 * 강의가 이의 제기 철회를 반영했다 (#278, D52·D71). 항목은 접수 전 상태로 돌아가 지급·구매 취소가 다시 가능하다.
 * 철회 명령 하나에 한 번 발행된다.
 * <p>
 * 받는 사람·제목·철회 시각은 알림용이다(#818, D83). 기존 필드 뒤에 붙였고, #818 배포 전에 저장된 발행 기록을 다시 보낸 것에는 없어
 * {@code null}이다. 그래서 레코드에서 {@code null}을 막지 않는다.
 *
 * @param commandId    처리한 철회 명령 ID
 * @param inquiryId    이의 제기 문의 ID
 * @param orderItemId  주문 항목 ID
 * @param buyerUserId  구매자 회원 ID
 * @param sellerUserId 판매자 회원 ID
 * @param lectureTitle 구매할 때의 강의 제목. 구매 뒤 강의 제목이 바뀌어도 그대로다
 * @param withdrawnAt  철회를 반영한 시각(KST offset). 철회 명령을 처리한 시각이다
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureObjectionWithdrawnEvent(String commandId, Long inquiryId, Long orderItemId, Long buyerUserId,
                                             Long sellerUserId, String lectureTitle, OffsetDateTime withdrawnAt) {

    public LectureObjectionWithdrawnEvent {
        withdrawnAt = LectureEventTimes.kst(withdrawnAt);
    }
}
