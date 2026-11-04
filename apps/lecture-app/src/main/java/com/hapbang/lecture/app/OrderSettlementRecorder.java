package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.out.LectureOrderRepository;

/**
 * 반환·지급 작업이 성공했음을 주문에 기록한다 (D59 (4), D70 (2)). 결과 처리기들이 작업 결과를 기록하는 트랜잭션 안에서 부른다.
 * 주문 행을 잠가(D46) 같은 주문의 다른 항목 반환과 겹쳐도 마지막 항목 판정(주문 {@code CANCELED})이 빠지지 않게 한다.
 */
@Component
public class OrderSettlementRecorder {

    private final LectureOrderRepository lectureOrderRepository;
    private final Clock clock;

    public OrderSettlementRecorder(LectureOrderRepository lectureOrderRepository,
                                   @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderRepository = lectureOrderRepository;
        this.clock = clock;
    }

    /**
     * 주문을 잠그고 항목을 완료 상태로 바꾼다.
     *
     * @param complete 주문과 지금 시각으로 항목을 바꾼다 (예: {@code (order, now) -> order.completeCancelItem(id, now)})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(LectureHpTask task, BiConsumer<LectureOrder, LocalDateTime> complete) {
        LectureOrder order = lectureOrderRepository.findForUpdateById(task.getOrderId())
                .orElseThrow(() -> new IllegalStateException("HP 작업의 주문이 없습니다. taskId=" + task.getId()
                        + ", orderId=" + task.getOrderId()));
        complete.accept(order, LocalDateTime.now(clock));
    }
}
