package com.hapbang.lecture.app;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;

import lombok.RequiredArgsConstructor;

/**
 * 구매 취소 반환({@code CANCEL}) 결과 처리기 (D59 (1), D65). 반환이 끝나면 항목이 구매 취소가 되고, 마지막 항목이면 주문도 취소된다.
 * {@code DEAD}가 되어도 항목은 처리 중 상태에 남고 처리 상태가 {@code ACTION_REQUIRED}가 된다. 관리자가 재시도한다 (D57).
 */
@Component
@RequiredArgsConstructor
public class CancelResultHandler implements LectureHpTaskResultHandler {

    private final OrderSettlementRecorder orderSettlementRecorder;

    @Override
    public LectureHpTaskPurpose purpose() {
        return LectureHpTaskPurpose.CANCEL;
    }

    @Override
    public void onSucceeded(LectureHpTask task) {
        orderSettlementRecorder.record(task, (order, now) -> order.completeCancelItem(task.getOrderItemId(), now));
    }
}
