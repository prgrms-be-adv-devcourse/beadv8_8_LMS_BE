package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.lecture.domain.LectureConfirmationType;

/**
 * 주문 항목의 구매가 확정됐다 (#872, D85). 직접 확정·재생 동의·자동 구매 확정 모두 항목마다 한 번 발행된다.
 * key는 {@code order-item:<주문 항목 ID>}다. 알림은 (주문 항목 ID, 구매자 ID)로 구매 취소 마감 임박 예약을 취소하고,
 * 자동 구매 확정 알림은 {@link LectureConfirmationType#AUTO}일 때만 만든다.
 *
 * @param orderItemId      주문 항목 ID
 * @param buyerUserId      구매자 회원 ID
 * @param lectureTitle     구매할 때의 강의 제목
 * @param confirmationType 확정 방식. 자동 구매 확정 시각이 지난 뒤의 직접 확정·재생 동의도 {@code AUTO}다(D34)
 * @param confirmedAt      확정 시각(KST offset). {@code AUTO}면 자동 구매 확정 시각(구매일 + 8일 00:00)이다
 */
@Externalized(LectureEventTopic.NAME + "::#{'order-item:' + #this.orderItemId()}")
public record LectureOrderItemConfirmedEvent(Long orderItemId, Long buyerUserId, String lectureTitle,
                                             LectureConfirmationType confirmationType,
                                             OffsetDateTime confirmedAt) {

    public LectureOrderItemConfirmedEvent {
        confirmedAt = LectureEventTimes.kst(confirmedAt);
    }
}
