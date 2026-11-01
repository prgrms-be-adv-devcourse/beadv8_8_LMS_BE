package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureOrderRepository;

/**
 * 구매 취소 (설계 6.4, D59 (1)). 세 단계로 나눠 호출한 Facade가 차례로 부른다.
 * <ol>
 *     <li>{@link #request}: 항목을 구매 취소 처리 중으로 바꾸고 RELEASE 작업을 등록한다(한 강의 트랜잭션).</li>
 *     <li>{@link #tryRelease}: 커밋 뒤 트랜잭션 밖에서 반환을 한 번 시도한다.</li>
 *     <li>{@link #result}: 항목을 다시 읽는다. 반환이 끝났으면 {@code CANCELED}, 아니면 {@code CANCEL_PENDING}이다.</li>
 * </ol>
 * 끝나지 않은 반환은 HP 작업 재시도 배치가 같은 요청 ID로 이어서 한다(D57). 반환은 billing이 차감했던 HP 종류 그대로 한다 (D42).
 */
@Service
public class OrderCancelUseCase {

    /** 반환 요청 ID. 항목은 한 번만 구매 취소되므로 항목 ID로 정해진다 (D43) */
    static final String RELEASE_REQUEST_ID_PREFIX = "LECTURE-CANCEL-";

    private final LectureOrderSupport lectureOrderSupport;
    private final LectureOrderRepository lectureOrderRepository;
    private final LectureHpTaskSupport lectureHpTaskSupport;
    private final LectureHpTaskExecutor lectureHpTaskExecutor;
    private final Clock clock;

    public OrderCancelUseCase(LectureOrderSupport lectureOrderSupport, LectureOrderRepository lectureOrderRepository,
                              LectureHpTaskSupport lectureHpTaskSupport, LectureHpTaskExecutor lectureHpTaskExecutor,
                              @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderSupport = lectureOrderSupport;
        this.lectureOrderRepository = lectureOrderRepository;
        this.lectureHpTaskSupport = lectureHpTaskSupport;
        this.lectureHpTaskExecutor = lectureHpTaskExecutor;
        this.clock = clock;
    }

    /** 구매 취소를 요청한다. 지갑은 부르지 않는다. 등록한 RELEASE 작업 ID를 돌려준다 */
    @Transactional
    public Long request(Long buyerUserId, Long orderId, Long orderItemId) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 반환 완료 기록이 주문을 잠그므로 같은 순서로 잠가 둔다 (D46)
        LectureOrder order = lectureOrderSupport.findOrderForUpdate(buyerUserId, orderId);
        OrderItem item = order.requestCancelItem(orderItemId, now);
        // 같은 항목을 동시에 바꾼 요청(낙관적 락 충돌)을 작업 등록 전에 확인한다 (설계 6.2)
        lectureOrderRepository.flush();
        return lectureHpTaskSupport.register(LectureHpTask.create(LectureHpTaskPurpose.CANCEL,
                RELEASE_REQUEST_ID_PREFIX + item.getId(), order.getId(), item.getId(), buyerUserId, null,
                item.getPriceHp(), now)).getId();
    }

    /** 반환을 한 번 시도한다. 트랜잭션 밖에서 부른다 */
    public void tryRelease(Long taskId) {
        lectureHpTaskExecutor.execute(taskId);
    }

    @Transactional(readOnly = true)
    public OrderItemResponse result(Long buyerUserId, Long orderItemId) {
        return lectureOrderSupport.toItemResponse(lectureOrderSupport.findItem(buyerUserId, orderItemId));
    }
}
