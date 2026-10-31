package com.hapbang.lecture.app;

import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureOrderProperties;
import com.hapbang.lecture.domain.*;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 (장바구니 주문·바로 구매, 설계 6.4, D58). 세 단계로 나눠 호출한 Facade가 차례로 부른다.
 * <ol>
 *     <li>{@link #place}: 주문·항목을 결제 처리 중으로 저장하고 항목마다 보류 작업을 등록한다(한 강의 트랜잭션).</li>
 *     <li>{@link #tryHolds}: 커밋 뒤 트랜잭션 밖에서 보류와 보상 반환을 한 번씩 시도한다. 시간 예산을 넘기면 멈춘다(D68).</li>
 *     <li>{@link #result}: 주문을 다시 읽어 결과를 낸다. 결제 실패면 {@code INSUFFICIENT_BALANCE}(409)다(D69 (1)).</li>
 * </ol>
 * 끝나지 않은 작업은 HP 작업 재시도 배치가 같은 요청 ID로 이어서 처리한다(D57).
 */
@Service
public class OrderCreateUseCase {

    /**
     * 보유 중인 강의의 중복 주문을 막는 부분 유일 인덱스 (D5, D58 (7))
     */
    private static final String OWNED_LECTURE_INDEX = "uk_order_item_buyer_lecture_owned";
    static final String HOLD_REQUEST_ID_PREFIX = "LECTURE-HOLD-";

    private final LectureRepository lectureRepository;
    private final LectureOrderRepository lectureOrderRepository;
    private final LectureHpTaskRepository lectureHpTaskRepository;
    private final LectureHpTaskSupport lectureHpTaskSupport;
    private final LectureHpTaskExecutor lectureHpTaskExecutor;
    private final LectureOrderSupport lectureOrderSupport;
    private final LectureOrderProperties properties;
    private final Clock clock;

    public OrderCreateUseCase(
            LectureRepository lectureRepository,
            LectureOrderRepository lectureOrderRepository,
            LectureHpTaskRepository lectureHpTaskRepository,
            LectureHpTaskSupport lectureHpTaskSupport,
            LectureHpTaskExecutor lectureHpTaskExecutor,
            LectureOrderSupport lectureOrderSupport,
            LectureOrderProperties properties,
            @Qualifier(LectureConfig.CLOCK) Clock clock
    ) {
        this.lectureRepository = lectureRepository;
        this.lectureOrderRepository = lectureOrderRepository;
        this.lectureHpTaskRepository = lectureHpTaskRepository;
        this.lectureHpTaskSupport = lectureHpTaskSupport;
        this.lectureHpTaskExecutor = lectureHpTaskExecutor;
        this.lectureOrderSupport = lectureOrderSupport;
        this.properties = properties;
        this.clock = clock;
    }

    /** 주문과 항목별 보류 작업을 한 트랜잭션에 저장한다. 지갑은 부르지 않는다 */
    @Transactional
    public Placement place(Long buyerUserId, List<Long> lectureIds) {
        // 항목을 강의 ID 오름차순으로 만들어 INSERT 순서를 같게 한다. 같은 구매자가 같은 강의들을 다른 순서로 동시에
        // 주문해도 늦은 쪽은 교착 없이 앞 주문을 기다렸다가 보유 강의 중복(D45)으로 끝난다 (D55)
        List<Long> sortedLectureIds = lectureIds.stream().sorted().toList();
        LocalDateTime now = LocalDateTime.now(clock);
        LectureOrder order = LectureOrder.place(
                buyerUserId, findLectures(sortedLectureIds),
                lectureOrderRepository.findHeldLectureIds(buyerUserId, sortedLectureIds), now
        );

        // 보류 작업의 참조 ID(주문 항목 ID)를 얻고, 같은 강의의 동시 주문을 작업 등록 전에 걸러낸다
        save(order);
        List<Long> holdTaskIds = order.getItems().stream()
                .map(item -> lectureHpTaskSupport.register(LectureHpTask.create(LectureHpTaskPurpose.ORDER_HOLD,
                        HOLD_REQUEST_ID_PREFIX + item.getId(), order.getId(), item.getId(), buyerUserId, null,
                        item.getPriceHp(), now)).getId())
                .toList();
        return new Placement(order.getId(), holdTaskIds);
    }

    /**
     * 항목 순서대로 보류를 한 번씩, 이어서 생긴 보상 반환을 한 번씩 시도한다. 트랜잭션 밖에서 부른다.
     * 시도하기 전에 남은 시간 예산을 보고, 넘었으면 남은 작업은 배치에 넘긴다. 시작한 호출은 끊지 않는다 (D68)
     */
    public void tryHolds(Placement placement) {
        Instant deadline = clock.instant().plus(properties.holdRequestBudget());
        for (Long holdTaskId : placement.holdTaskIds()) {
            if (!tryWithin(deadline, holdTaskId)) {
                return;
            }
        }
        for (Long compensationTaskId : pendingCompensationIds(placement.orderId())) {
            if (!tryWithin(deadline, compensationTaskId)) {
                return;
            }
        }
    }

    /** 주문 결과. 결제 실패면 묶여 남은 HP가 없음이 확인된 것이라 잔액 부족으로 응답한다 (D58 (4), D69 (1)) */
    @Transactional(readOnly = true)
    public OrderResponse result(Long buyerUserId, Long orderId) {
        LectureOrder order = lectureOrderSupport.findOrder(buyerUserId, orderId);
        if (order.getStatus() == LectureOrderStatus.HOLD_FAILED) {
            throw new LectureException(LectureErrorCode.INSUFFICIENT_BALANCE);
        }
        return lectureOrderSupport.toResponse(order);
    }

    private boolean tryWithin(Instant deadline, Long taskId) {
        if (!clock.instant().isBefore(deadline)) {
            return false;
        }
        lectureHpTaskExecutor.execute(taskId);
        return true;
    }

    private List<Long> pendingCompensationIds(Long orderId) {
        return lectureHpTaskRepository.findByOrderIdIn(List.of(orderId)).stream()
                .filter(task -> task.getPurpose() == LectureHpTaskPurpose.HOLD_COMPENSATION)
                .filter(task -> task.getStatus() == LectureHpTaskStatus.PENDING)
                .map(LectureHpTask::getId)
                .sorted()
                .toList();
    }

    /**
     * 받은 순서대로 강의를 읽는다. 같은 강의가 두 번 있으면 그대로 넘겨 도메인이 거부하게 한다(D31).
     * 없는 강의는 판매 중이 아닌 강의와 같게 본다.
     */
    private List<Lecture> findLectures(List<Long> lectureIds) {
        Map<Long, Lecture> lectures = lectureRepository.findAllById(lectureIds)
                .stream()
                .collect(Collectors.toMap(Lecture::getId, Function.identity()));
        if (!lectures.keySet().containsAll(lectureIds)) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        return lectureIds.stream().map(lectures::get).toList();
    }

    /**
     * 보유 검사를 동시에 통과한 같은 강의 주문은 부분 유일 인덱스에 걸린다. 이미 보유한 강의로 응답한다 (D45)
     */
    private void save(LectureOrder order) {
        try {
            lectureOrderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            if (UniqueIndexViolation.isOf(e, OWNED_LECTURE_INDEX)) {
                throw new LectureException(LectureErrorCode.ALREADY_OWNED_LECTURE);
            }
            throw e;
        }
    }

    /** 저장한 주문과 항목 순서대로의 보류 작업 ID */
    public record Placement(Long orderId, List<Long> holdTaskIds) {
    }
}
