package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;

import lombok.RequiredArgsConstructor;

/**
 * 구매 취소 반환({@code CANCEL}) 결과 처리기 (D59 (1), D65). 반환이 끝나면 항목이 구매 취소가 되고, 마지막 항목이면 주문도 취소된다.
 * {@code DEAD}가 되어도 항목은 처리 중 상태에 남고 처리 상태가 {@code ACTION_REQUIRED}가 된다. 관리자가 재시도한다 (D57).
 */
@Component
@RequiredArgsConstructor
public class CancelResultHandler implements LectureHpTaskResultHandler {

    private final OrderSettlementRecorder orderSettlementRecorder;
    private final LectureSyncEvents lectureSyncEvents;
    private final OrderCouponEvents orderCouponEvents;

    @Override
    public LectureHpTaskPurpose purpose() {
        return LectureHpTaskPurpose.CANCEL;
    }

    @Override
    public void onSucceeded(LectureHpTask task) {
        orderSettlementRecorder.record(task, (order, now) -> complete(order, task.getOrderItemId(), task.getRequestId(),
                now));
    }

    /**
     * 구매 취소 완료를 기록한다. 반환 작업 성공과, 반환할 HP가 없는 항목(구매자 금액 0)의 즉시 완료가 같이 쓴다.
     * 호출한 쪽이 주문 행을 잠근 트랜잭션에서 부른다. 쿠폰을 쓴 항목이면 같은 트랜잭션에서 취소 완료 이벤트를 낸다.
     *
     * @param releaseRequestId 반환한 지갑 작업의 요청 ID. 작업 없이 끝났으면 {@code null}
     */
    void complete(LectureOrder order, Long orderItemId, String releaseRequestId, LocalDateTime now) {
        OrderItem item = order.completeCancelItem(orderItemId, now);
        lectureSyncEvents.purchaseChanged(item);
        orderCouponEvents.itemCancelCompleted(order, item, releaseRequestId, now);
    }
}
