package com.hapbang.lecture.app;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;

import lombok.RequiredArgsConstructor;

/**
 * 판매자 지급({@code PAYOUT}) 결과 처리기 (D59 (5), D65). 지급이 끝나면 항목이 지급 완료가 된다.
 * {@code DEAD}가 되어도 항목은 처리 중 상태에 남고 처리 상태가 {@code ACTION_REQUIRED}가 된다. 관리자가 재시도한다 (D57).
 */
@Component
@RequiredArgsConstructor
public class PayoutResultHandler implements LectureHpTaskResultHandler {

    private final OrderSettlementRecorder orderSettlementRecorder;

    @Override
    public LectureHpTaskPurpose purpose() {
        return LectureHpTaskPurpose.PAYOUT;
    }

    @Override
    public void onSucceeded(LectureHpTask task) {
        orderSettlementRecorder.record(task, (order, now) -> order.completePayoutItem(task.getOrderItemId(), now));
    }
}
