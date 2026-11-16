package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.shared.lecture.event.LectureObjectionFiledEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이의 제기 접수·결과 이벤트({@code lecture.events.v1})를 주문 항목으로 만들어 발행한다 (#278, D71, #818, D83, #872, D85).
 * <ul>
 *     <li>업무 트랜잭션 안에서, 항목을 바꾼 뒤에 부른다. 발행 기록은 Modulith 발행 기록에 남고 커밋 뒤 Kafka로 간다.</li>
 *     <li>고객센터는 문의 ID·명령 ID로, 알림은 받는 사람 ID·강의 제목·사건 시각으로 쓴다. 강의 제목은 구매할 때 저장한 제목이다.</li>
 *     <li>사건 시각은 강의 시각(KST)에 offset({@code +09:00})을 붙인 값이다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LectureObjectionEvents {

    private final ApplicationEventPublisher eventPublisher;

    /** 이의 제기를 접수했다(알림 ND-015). 문의 ID와 접수 시각은 항목에 저장한 값이다 */
    public void filed(OrderItem item) {
        eventPublisher.publishEvent(new LectureObjectionFiledEvent(item.getObjectionInquiryId(), item.getId(),
                item.getBuyerUserId(), item.getSellerUserId(), item.getLectureTitleSnapshot(),
                kst(item.getObjectionFiledAt())));
    }

    /** 판단을 반영했다. 판단 시각은 항목에 저장한 판단 시각이다 */
    public void resolved(String commandId, Long inquiryId, boolean accepted, OrderItem item) {
        eventPublisher.publishEvent(new LectureObjectionResolvedEvent(commandId, inquiryId, item.getId(), accepted,
                item.getBuyerUserId(), item.getSellerUserId(), item.getLectureTitleSnapshot(),
                kst(item.getObjectionResolvedAt())));
    }

    /**
     * 철회를 반영했다. 철회하면 항목의 문의 ID가 지워지므로 명령의 문의 ID를 받는다.
     *
     * @param withdrawnAt 철회 명령을 처리한 시각. 항목에는 철회 시각 칸이 없다
     */
    public void withdrawn(String commandId, Long inquiryId, OrderItem item, LocalDateTime withdrawnAt) {
        eventPublisher.publishEvent(new LectureObjectionWithdrawnEvent(commandId, inquiryId, item.getId(),
                item.getBuyerUserId(), item.getSellerUserId(), item.getLectureTitleSnapshot(), kst(withdrawnAt)));
    }

    /** 이의 제기 인정 반환이 끝났다. 완료 시각은 항목의 반환 시각이다 */
    public void returnCompleted(OrderItem item) {
        publishForInquiry(item, new LectureObjectionReturnCompletedEvent(item.getObjectionInquiryId(), item.getId(),
                item.getBuyerUserId(), item.getSellerUserId(), item.getLectureTitleSnapshot(),
                kst(item.getReturnedAt())));
    }

    /**
     * 이의 제기 인정 반환이 멈췄다. 회차는 항목에 저장한 반환 지연 회차라, 같은 이벤트를 다시 보내도 같다.
     *
     * @param occurredAt 반환 작업을 DEAD로 바꾼 시각
     */
    public void returnActionRequired(OrderItem item, LocalDateTime occurredAt) {
        publishForInquiry(item, new LectureObjectionReturnActionRequiredEvent(item.getObjectionInquiryId(),
                item.getId(), item.getBuyerUserId(), item.getSellerUserId(), item.getLectureTitleSnapshot(),
                item.getReturnDelayCount(), kst(occurredAt)));
    }

    /** 반환 작업은 이의 제기 인정으로만 생기므로 문의 ID가 있다. 없으면 알릴 문의가 없어 로그만 남긴다 */
    private void publishForInquiry(OrderItem item, Object event) {
        if (item.getObjectionInquiryId() == null) {
            log.error("이의 제기 문의 ID가 없는 반환이라 고객센터에 알리지 않는다 orderItemId={}, eventType={}", item.getId(),
                    event.getClass().getSimpleName());
            return;
        }
        eventPublisher.publishEvent(event);
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return KstTimes.offset(time);
    }
}
