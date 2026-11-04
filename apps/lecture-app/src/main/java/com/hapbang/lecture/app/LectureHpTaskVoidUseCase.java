package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpTask;

import lombok.extern.slf4j.Slf4j;

/**
 * 관리자의 보류 무효화: 결과를 확정하지 못한 주문 항목 보류({@code DEAD}인 {@code ORDER_HOLD})를 보류 무효화로 끝낸다 (D73).
 * 원래 보류를 {@code VOIDED}로 닫고, 주문을 결제 취소 처리 중으로 바꾸고, 무효화 작업을 등록한다. 무효화 작업은 바로 실행하지 않고
 * 다음 재시도 배치(기본 1분 안)가 보낸다. 지갑 무효화는 되돌릴 수 없으므로 재시도로 결과가 바뀌지 않을 때만 쓴다.
 * 호출한 쪽 트랜잭션에서 실행한다.
 */
@Slf4j
@Service
public class LectureHpTaskVoidUseCase {

    private final LectureHpTaskSupport lectureHpTaskSupport;
    private final OrderHoldSettlement orderHoldSettlement;
    private final Clock clock;

    public LectureHpTaskVoidUseCase(LectureHpTaskSupport lectureHpTaskSupport, OrderHoldSettlement orderHoldSettlement,
                                    @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureHpTaskSupport = lectureHpTaskSupport;
        this.orderHoldSettlement = orderHoldSettlement;
        this.clock = clock;
    }

    /** @return 등록한 무효화 작업 */
    public LectureHpTask voidHold(Long adminUserId, Long taskId) {
        LectureHpTask hold = lectureHpTaskSupport.getById(taskId);
        hold.voidByAdmin(adminUserId, LocalDateTime.now(clock));
        LectureHpTask voidTask = orderHoldSettlement.voidHold(hold);
        AfterCommit.run(() -> {
            log.info("관리자가 보류 무효화 작업을 만들었다 holdTaskId={}, voidTaskId={}, orderId={}, orderItemId={}, "
                            + "lastErrorCode={}, adminUserId={}", hold.getId(), voidTask.getId(), hold.getOrderId(),
                    hold.getOrderItemId(), hold.getLastErrorCode(), adminUserId);
        });
        return voidTask;
    }
}
