package com.hapbang.lecture.app;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;

import lombok.RequiredArgsConstructor;

/**
 * 보류 보상 반환({@code HOLD_COMPENSATION}) 결과 처리기 (D58, D65). 반환이 모두 끝나면 주문이 결제 실패가 된다.
 * {@code DEAD}가 되어도 주문은 결제 취소 처리 중에 남는다.
 */
@Component
@RequiredArgsConstructor
public class HoldCompensationResultHandler implements LectureHpTaskResultHandler {

    private final OrderHoldSettlement orderHoldSettlement;

    @Override
    public LectureHpTaskPurpose purpose() {
        return LectureHpTaskPurpose.HOLD_COMPENSATION;
    }

    @Override
    public void onSucceeded(LectureHpTask task) {
        orderHoldSettlement.settle(task);
    }
}
