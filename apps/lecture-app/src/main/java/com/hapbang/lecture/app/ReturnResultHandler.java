package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.shared.billing.dto.PromotionRestorationDto;

import lombok.RequiredArgsConstructor;

/**
 * 이의 제기 인정 반환({@code RETURN}) 결과 처리기 (D59 (1), D65). 반환이 끝나면 항목이 반환이 되고, 마지막 항목이면 주문도 취소된다.
 * {@code DEAD}가 되어도 항목은 처리 중 상태에 남고 처리 상태가 {@code ACTION_REQUIRED}가 된다. 관리자가 재시도한다 (D57).
 * <p>
 * 반환 완료·자동 처리 중단은 결과를 기록하는 같은 트랜잭션에서 강의 이벤트로 고객센터·알림에 알린다(#278, D71, #818, D83).
 * 자동 처리 중단은 항목의 반환 지연 회차를 올리고 그 회차를 싣는다. 관리자 재시도 뒤 다시 멈추면 회차가 하나 커진 이벤트가 또 나간다.
 * 고객센터는 문의의 HP 반환 상태를 앞으로만 바꾸므로 다시 와도 되고, 알림은 회차로 새 지연인지 가린다.
 */
@Component
@RequiredArgsConstructor
public class ReturnResultHandler implements LectureHpTaskResultHandler {

    private final OrderSettlementRecorder orderSettlementRecorder;
    private final LectureSyncEvents lectureSyncEvents;
    private final LectureOrderRepository lectureOrderRepository;
    private final LectureObjectionEvents lectureObjectionEvents;
    private final OrderCouponEvents orderCouponEvents;

    @Override
    public LectureHpTaskPurpose purpose() {
        return LectureHpTaskPurpose.RETURN;
    }

    @Override
    public void onSucceeded(LectureHpTask task) {
        onSucceeded(task, List.of());
    }

    @Override
    public void onSucceeded(LectureHpTask task, List<PromotionRestorationDto> restorations) {
        orderSettlementRecorder.record(task, (order, now) -> complete(order, task.getOrderItemId(), task.getRequestId(),
                restorations, now));
    }

    /**
     * 반환 완료를 기록하고 고객센터에 알린다. 반환 작업 성공과, 구매자·플랫폼 반환 HP가 모두 없는 항목의 즉시 완료가 같이 쓴다.
     * 호출한 쪽이 주문 행을 잠근 트랜잭션에서 부른다. 쿠폰을 쓴 항목이면 같은 트랜잭션에서 취소 완료 이벤트를 낸다(쿠폰 복원).
     *
     * @param releaseRequestId 반환한 지갑 작업의 요청 ID. 작업 없이 끝났으면 {@code null}
     */
    void complete(LectureOrder order, Long orderItemId, String releaseRequestId, LocalDateTime now) {
        complete(order, orderItemId, releaseRequestId, List.of(), now);
    }

    private void complete(LectureOrder order, Long orderItemId, String releaseRequestId,
                          List<PromotionRestorationDto> restorations, LocalDateTime now) {
        OrderItem item = order.completeReturnItem(orderItemId, now);
        // 반환 처리 중에서 반환으로 바뀌어도 리뷰는 평점에서 빠진 그대로라 리뷰는 다시 보내지 않는다
        lectureSyncEvents.purchaseChanged(item);
        lectureObjectionEvents.returnCompleted(item);
        orderCouponEvents.itemCancelCompleted(order, item, releaseRequestId, restorations, now);
    }

    /**
     * 작업을 DEAD로 바꾸는 트랜잭션에서 DEAD 전이 한 번에 한 번 불린다(결과 기록은 선점 표식·낙관적 락으로, 선점 만료 DEAD는 조건부
     * UPDATE로 한 번만 지난다). 그래서 회차도 DEAD 한 번에 하나 오른다. 주문은 닫지 않아 잠그지 않는다
     */
    @Override
    public void onDead(LectureHpTask task) {
        LectureOrder order = lectureOrderRepository.findByItemId(task.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException("반환 작업의 주문 항목이 없습니다. taskId=" + task.getId()
                        + ", orderItemId=" + task.getOrderItemId()));
        OrderItem item = order.recordReturnDelay(task.getOrderItemId());
        lectureObjectionEvents.returnActionRequired(item, task.getLastFailedAt());
    }
}
