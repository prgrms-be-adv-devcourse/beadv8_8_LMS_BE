package com.hapbang.lecture.app;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;

import lombok.RequiredArgsConstructor;

/**
 * 주문 항목 보류({@code ORDER_HOLD}) 결과 처리기 (D58, D65). {@code DEAD}가 되어도 주문은 결제 단계에 남고
 * 처리 상태가 {@code ACTION_REQUIRED}가 된다. 관리자가 재시도한다.
 */
@Component
@RequiredArgsConstructor
public class OrderHoldResultHandler implements LectureHpTaskResultHandler {

    private final OrderHoldSettlement orderHoldSettlement;

    @Override
    public LectureHpTaskPurpose purpose() {
        return LectureHpTaskPurpose.ORDER_HOLD;
    }

    @Override
    public void onSucceeded(LectureHpTask task) {
        orderHoldSettlement.settle(task);
    }

    @Override
    public void onRejected(LectureHpTask task) {
        orderHoldSettlement.reject(task);
    }
}
