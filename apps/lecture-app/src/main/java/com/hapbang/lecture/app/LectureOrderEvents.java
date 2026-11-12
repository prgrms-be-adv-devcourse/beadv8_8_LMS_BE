package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.domain.ConfirmationType;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;
import com.hapbang.shared.lecture.event.LectureOrderHoldFailedEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemCanceledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;

import lombok.RequiredArgsConstructor;

/**
 * 주문·구매 이벤트({@code lecture.events.v1})를 주문과 주문 항목으로 만들어 발행한다 (#872, D85). 알림이 구매 완료·결제 실패·구매 취소·
 * 자동 구매 확정을 구매자에게 알리고, 주문 항목마다 구매 취소 마감 임박 예약을 만들고 취소한다.
 * <ul>
 *     <li>업무 트랜잭션 안에서, 상태를 바꾼 뒤에 부른다. 발행 기록은 Modulith 발행 기록에 남고 커밋 뒤 Kafka로 간다.</li>
 *     <li>제목은 주문할 때 저장한 제목이고, 금액은 구매자가 내는 HP(가격 − 쿠폰 할인)다.</li>
 *     <li>사건 시각은 주문·항목에 저장한 시각이다. 결제 실패만 저장 칸이 없어 기록한 시각을 받는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LectureOrderEvents {

    private final ApplicationEventPublisher eventPublisher;

    /** 주문의 모든 항목 결제가 끝났다. 구매 시각은 주문의 결제 시각이다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void paid(LectureOrder order) {
        eventPublisher.publishEvent(new LectureOrderPaidEvent(order.getId(), order.getBuyerUserId(), items(order),
                KstTimes.offset(order.getPaidAt())));
    }

    /**
     * 주문이 결제 실패로 끝났다. 결제 실패로 바꾸는 곳(보류 결과, 쿠폰 예약 거절·불일치, 관리자 실패 확정)마다 부른다.
     *
     * @param failedAt 결제 실패를 기록한 시각. 주문에는 결제 실패 시각 칸이 없다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdFailed(LectureOrder order, LocalDateTime failedAt) {
        eventPublisher.publishEvent(new LectureOrderHoldFailedEvent(order.getId(), order.getBuyerUserId(),
                items(order), KstTimes.offset(failedAt)));
    }

    /** 구매 취소의 반환이 끝났다. 취소 시각은 항목의 구매 취소 시각이다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void itemCanceled(OrderItem item) {
        eventPublisher.publishEvent(new LectureOrderItemCanceledEvent(item.getId(), item.getBuyerUserId(),
                item.getLectureTitleSnapshot(), item.buyerAmountHp(), KstTimes.offset(item.getCanceledAt())));
    }

    /** 구매가 확정됐다(직접·재생 동의·자동). 확정 방식과 시각은 항목에 기록한 값이다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void itemConfirmed(OrderItem item) {
        eventPublisher.publishEvent(new LectureOrderItemConfirmedEvent(item.getId(), item.getBuyerUserId(),
                item.getLectureTitleSnapshot(), confirmationType(item.getConfirmationType()),
                KstTimes.offset(item.getConfirmedAt())));
    }

    /**
     * 도메인 확정 방식을 이벤트 값으로 바꾼다. 이름으로 바꾸지 않고 하나씩 짝지어, 도메인에 확정 방식이 늘면 확정 트랜잭션이 실행 중에 깨지는 대신
     * 여기서 컴파일이 실패하게 한다(#891 리뷰)
     */
    private static LectureConfirmationType confirmationType(ConfirmationType type) {
        return switch (type) {
            case DIRECT -> LectureConfirmationType.DIRECT;
            case FIRST_PLAY -> LectureConfirmationType.FIRST_PLAY;
            case AUTO -> LectureConfirmationType.AUTO;
        };
    }

    private static List<LectureOrderItemDto> items(LectureOrder order) {
        return order.getItems().stream()
                .map(item -> new LectureOrderItemDto(item.getId(), item.getLectureId(), item.getLectureTitleSnapshot(),
                        item.buyerAmountHp()))
                .toList();
    }
}
