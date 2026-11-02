package com.hapbang.lecture.app;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;

import lombok.RequiredArgsConstructor;

/**
 * 보류 무효화({@code HOLD_VOID}) 결과 처리기 (D73, D65). 무효화와 다른 항목의 보상 반환이 모두 끝나면 주문이 결제 실패가 된다.
 * 지갑 결과(반환·이미 끝남·무효 표시)는 실행기가 확인하고, 어느 결과든 그 항목에는 묶여 남은 HP가 없다.
 * {@code DEAD}가 되어도 주문은 결제 취소 처리 중에 남는다. 관리자가 이 작업을 재시도한다.
 */
@Component
@RequiredArgsConstructor
public class HoldVoidResultHandler implements LectureHpTaskResultHandler {

    private final OrderHoldSettlement orderHoldSettlement;

    @Override
    public LectureHpTaskPurpose purpose() {
        return LectureHpTaskPurpose.HOLD_VOID;
    }

    @Override
    public void onSucceeded(LectureHpTask task) {
        orderHoldSettlement.settle(task);
    }
}
