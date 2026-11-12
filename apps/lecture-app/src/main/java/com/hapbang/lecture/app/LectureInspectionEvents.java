package com.hapbang.lecture.app;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.shared.lecture.event.LectureApprovedEvent;
import com.hapbang.shared.lecture.event.LectureRejectedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 검수 결과 이벤트({@code lecture.events.v1})를 강의로 만들어 발행한다 (#872, D85). 알림이 검수 승인·반려를 판매자에게 알린다.
 * <ul>
 *     <li>업무 트랜잭션 안에서, 강의를 승인·반려한 뒤에 부른다. 발행 기록은 Modulith 발행 기록에 남고 커밋 뒤 Kafka로 간다.</li>
 *     <li>검수 회차는 강의에 저장한 값이라 같은 이벤트를 다시 보내도 같다. 사건 시각은 강의의 검수 시각이다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LectureInspectionEvents {

    private final ApplicationEventPublisher eventPublisher;

    /** 검수를 승인했다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(Lecture lecture) {
        eventPublisher.publishEvent(new LectureApprovedEvent(lecture.getId(), lecture.getSellerUserId(),
                lecture.getTitle(), lecture.getInspectionCount(), KstTimes.offset(lecture.getInspectedAt())));
    }

    /** 검수를 반려했다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(Lecture lecture) {
        eventPublisher.publishEvent(new LectureRejectedEvent(lecture.getId(), lecture.getSellerUserId(),
                lecture.getTitle(), lecture.getRejectionReason(), lecture.getInspectionCount(),
                KstTimes.offset(lecture.getInspectedAt())));
    }
}
