package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;

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

    @Override
    public LectureHpTaskPurpose purpose() {
        return LectureHpTaskPurpose.CANCEL;
    }

    @Override
    public void onSucceeded(LectureHpTask task) {
        orderSettlementRecorder.record(task, (order, now) -> complete(order, task.getOrderItemId(), now));
    }

    /**
     * 구매 취소 완료를 기록한다. 반환 작업 성공과, 반환할 HP가 없는 항목(구매자 금액 0)의 즉시 완료가 같이 쓴다.
     * 호출한 쪽이 주문 행을 잠근 트랜잭션에서 부른다
     */
    void complete(LectureOrder order, Long orderItemId, LocalDateTime now) {
        lectureSyncEvents.purchaseChanged(order.completeCancelItem(orderItemId, now));
    }
}
