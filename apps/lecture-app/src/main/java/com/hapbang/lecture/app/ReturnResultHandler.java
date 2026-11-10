package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이의 제기 인정 반환({@code RETURN}) 결과 처리기 (D59 (1), D65). 반환이 끝나면 항목이 반환이 되고, 마지막 항목이면 주문도 취소된다.
 * {@code DEAD}가 되어도 항목은 처리 중 상태에 남고 처리 상태가 {@code ACTION_REQUIRED}가 된다. 관리자가 재시도한다 (D57).
 * <p>
 * 반환 완료·자동 처리 중단은 결과를 기록하는 같은 트랜잭션에서 강의 이벤트로 고객센터에 알린다(#278, D71).
 * 고객센터는 문의의 HP 반환 상태를 앞으로만 바꾸므로, 관리자 재시도 뒤 다시 멈춰 같은 이벤트가 또 나가도 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnResultHandler implements LectureHpTaskResultHandler {

    private final OrderSettlementRecorder orderSettlementRecorder;
    private final LectureSyncEvents lectureSyncEvents;
    private final LectureOrderRepository lectureOrderRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderCouponEvents orderCouponEvents;

    @Override
    public LectureHpTaskPurpose purpose() {
        return LectureHpTaskPurpose.RETURN;
    }

    @Override
    public void onSucceeded(LectureHpTask task) {
        orderSettlementRecorder.record(task, (order, now) -> complete(order, task.getOrderItemId(), task.getRequestId(),
                now));
    }

    /**
     * 반환 완료를 기록하고 고객센터에 알린다. 반환 작업 성공과, 반환할 HP가 없는 항목(구매자 금액 0)의 즉시 완료가 같이 쓴다.
     * 호출한 쪽이 주문 행을 잠근 트랜잭션에서 부른다. 쿠폰을 쓴 항목이면 같은 트랜잭션에서 취소 완료 이벤트를 낸다(쿠폰 복원).
     *
     * @param releaseRequestId 반환한 지갑 작업의 요청 ID. 작업 없이 끝났으면 {@code null}
     */
    void complete(LectureOrder order, Long orderItemId, String releaseRequestId, LocalDateTime now) {
        OrderItem item = order.completeReturnItem(orderItemId, now);
        // 반환 처리 중에서 반환으로 바뀌어도 리뷰는 평점에서 빠진 그대로라 리뷰는 다시 보내지 않는다
        lectureSyncEvents.purchaseChanged(item);
        publish(item, new LectureObjectionReturnCompletedEvent(item.getObjectionInquiryId(), item.getId()));
        orderCouponEvents.itemCancelCompleted(order, item, releaseRequestId, now);
    }

    @Override
    public void onDead(LectureHpTask task) {
        OrderItem item = lectureOrderRepository.findItemOfBuyer(task.getOrderItemId(), task.getPayerUserId())
                .orElseThrow(() -> new IllegalStateException("반환 작업의 주문 항목이 없습니다. taskId=" + task.getId()
                        + ", orderItemId=" + task.getOrderItemId()));
        publish(item, new LectureObjectionReturnActionRequiredEvent(item.getObjectionInquiryId(), item.getId()));
    }

    /** 반환 작업은 이의 제기 인정으로만 생기므로 문의 ID가 있다. 없으면 알릴 문의가 없어 로그만 남긴다 */
    private void publish(OrderItem item, Object event) {
        if (item.getObjectionInquiryId() == null) {
            log.error("이의 제기 문의 ID가 없는 반환이라 고객센터에 알리지 않는다 orderItemId={}, eventType={}", item.getId(),
                    event.getClass().getSimpleName());
            return;
        }
        eventPublisher.publishEvent(event);
    }
}
