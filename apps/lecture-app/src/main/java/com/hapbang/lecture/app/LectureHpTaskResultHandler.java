package com.hapbang.lecture.app;

import java.util.List;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.shared.billing.dto.PromotionRestorationDto;

/**
 * HP 작업 결과를 업무 상태에 반영한다. 작업 목적마다 하나다 (D65).
 * <ul>
 *     <li>작업 결과를 기록하는 짧은 강의 트랜잭션 안에서 부른다. 예외를 던지면 결과 기록도 롤백되고,
 *     선점이 만료된 뒤 같은 요청 ID로 다시 실행한다.</li>
 *     <li>처리기가 없는 목적의 작업은 지갑을 부르지 않고 {@code DEAD}로 둔다(결과를 아무도 반영하지 않는 것을 막는다).</li>
 * </ul>
 */
public interface LectureHpTaskResultHandler {

    LectureHpTaskPurpose purpose();

    /** 지갑이 처리했다 */
    void onSucceeded(LectureHpTask task);

    /** 반환·무효화가 확인한 플랫폼 예산 복구 결과. 결과 기록과 이벤트 발행은 같은 트랜잭션이다. */
    default void onSucceeded(LectureHpTask task, List<PromotionRestorationDto> restorations) {
        onSucceeded(task);
    }

    /** 보류가 확정 거절됐다(잔액 부족, 보류 무효화된 대상). 보류 목적만 받는다 */
    default void onRejected(LectureHpTask task) {
    }

    /** 자동 처리가 멈췄다. 관리자 재시도로 다시 {@code PENDING}이 될 수 있다 */
    default void onDead(LectureHpTask task) {
    }
}
