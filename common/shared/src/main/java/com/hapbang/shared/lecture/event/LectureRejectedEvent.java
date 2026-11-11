package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;

import org.springframework.modulith.events.Externalized;

/**
 * 관리자가 강의 검수를 반려했다 (#872, D85). 판매자는 고친 뒤 재검수를 신청할 수 있다. 검수 회차마다 한 번 발행된다.
 * 알림은 {@code <강의 ID>:<검수 회차>}로 같은 검수 결과를 한 번만 알린다(ND-045). key는 {@code lecture:<강의 ID>}다.
 *
 * @param lectureId          강의 ID
 * @param sellerUserId       판매자 회원 ID
 * @param lectureTitle       반려할 때의 강의 제목
 * @param rejectionReason    관리자가 쓴 반려 사유
 * @param inspectionSequence 검수 회차. 등록이 1회차이고 재검수를 신청할 때마다 1씩 늘어난다
 * @param rejectedAt         반려 시각(KST offset)
 */
@Externalized(LectureEventTopic.NAME + "::#{'lecture:' + #this.lectureId()}")
public record LectureRejectedEvent(Long lectureId, Long sellerUserId, String lectureTitle, String rejectionReason,
                                   Integer inspectionSequence, OffsetDateTime rejectedAt) {

    public LectureRejectedEvent {
        rejectedAt = LectureEventTimes.kst(rejectedAt);
    }
}
