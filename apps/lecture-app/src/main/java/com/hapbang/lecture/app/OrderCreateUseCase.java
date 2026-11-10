package com.hapbang.lecture.app;

import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureOrderProperties;
import com.hapbang.lecture.domain.*;
import com.hapbang.lecture.out.LectureHpTaskAttemptRepository;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.OrderCouponWorkRepository;
import com.hapbang.lecture.out.OrderItemCouponRepository;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * 주문 (장바구니 주문·바로 구매, 설계 6.4, D58). 세 단계로 나눠 호출한 Facade가 차례로 부른다.
 * <ol>
 *     <li>{@link #place}: 주문·항목을 결제 처리 중으로 저장하고 항목마다 보류 작업을 등록한다(한 강의 트랜잭션).
 *     쿠폰 주문은 {@link #placeWithCoupons}로 저장하고, 보류 작업은 쿠폰 예약 결과를 저장한 뒤에 등록한다.</li>
 *     <li>{@link #tryHolds}: 커밋 뒤 트랜잭션 밖에서 보류와 보상 반환을 한 번씩 시도한다. 시간 예산을 넘기면 멈춘다(D68).</li>
 *     <li>{@link #result}: 주문을 다시 읽어 결과를 낸다. 결제 실패면 {@code INSUFFICIENT_BALANCE}(409)이고, 탈퇴로 닫힌 지갑이 거절했으면
 *     {@code WALLET_CLOSED}(409)다(D69 (1), D76).</li>
 * </ol>
 * 끝나지 않은 작업은 HP 작업 재시도 배치가 같은 요청 ID로 이어서 처리한다(D57).
 */
@Slf4j
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
    private final LectureHpTaskAttemptRepository lectureHpTaskAttemptRepository;
    private final LectureHpTaskSupport lectureHpTaskSupport;
    private final LectureHpTaskExecutor lectureHpTaskExecutor;
    private final LectureOrderSupport lectureOrderSupport;
    private final LectureOrderProperties properties;
    private final LectureSyncEvents lectureSyncEvents;
    private final OrderItemCouponRepository orderItemCouponRepository;
    private final OrderCouponWorkRepository orderCouponWorkRepository;
    private final Clock clock;

    public OrderCreateUseCase(
            LectureRepository lectureRepository,
            LectureOrderRepository lectureOrderRepository,
            LectureHpTaskRepository lectureHpTaskRepository,
            LectureHpTaskAttemptRepository lectureHpTaskAttemptRepository,
            LectureHpTaskSupport lectureHpTaskSupport,
            LectureHpTaskExecutor lectureHpTaskExecutor,
            LectureOrderSupport lectureOrderSupport,
            LectureOrderProperties properties,
            LectureSyncEvents lectureSyncEvents,
            OrderItemCouponRepository orderItemCouponRepository,
            OrderCouponWorkRepository orderCouponWorkRepository,
            @Qualifier(LectureConfig.CLOCK) Clock clock
    ) {
        this.lectureRepository = lectureRepository;
        this.lectureOrderRepository = lectureOrderRepository;
        this.lectureHpTaskRepository = lectureHpTaskRepository;
        this.lectureHpTaskAttemptRepository = lectureHpTaskAttemptRepository;
        this.lectureHpTaskSupport = lectureHpTaskSupport;
        this.lectureHpTaskExecutor = lectureHpTaskExecutor;
        this.lectureOrderSupport = lectureOrderSupport;
        this.properties = properties;
        this.lectureSyncEvents = lectureSyncEvents;
        this.orderItemCouponRepository = orderItemCouponRepository;
        this.orderCouponWorkRepository = orderCouponWorkRepository;
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
        lectureSyncEvents.purchasesChanged(order.getItems());
        List<Long> holdTaskIds = order.getItems().stream()
                .map(item -> lectureHpTaskSupport.register(holdTask(order, item, now)).getId())
                .toList();
        AfterCommit.run(() -> {
            log.info("주문을 만들었다 orderId={}, buyerUserId={}, itemCount={}, totalHp={}", order.getId(), buyerUserId,
                    order.getItems().size(), order.getItems().stream().mapToLong(OrderItem::getPriceHp).sum());
        });
        return new Placement(order.getId(), holdTaskIds);
    }

    /**
     * 주문 항목 하나의 보류 작업. 금액은 구매자 금액(가격 − 할인)이다. 쿠폰 주문의 예약 뒤 보류 등록({@link OrderCouponReserveUseCase})도
     * 이것으로 만든다. 구매자 금액이 0인 항목({@link OrderItem#requiresBuyerHpTask()}가 거짓)은 부르지 않는다
     */
    static LectureHpTask holdTask(LectureOrder order, OrderItem item, LocalDateTime now) {
        return LectureHpTask.create(LectureHpTaskPurpose.ORDER_HOLD, HOLD_REQUEST_ID_PREFIX + item.getId(),
                order.getId(), item.getId(), order.getBuyerUserId(), null, item.buyerAmountHp(), now);
    }

    /**
     * 쿠폰 주문 1단계: 주문·항목과 할인 스냅샷, 쿠폰별 계산 근거(계산 때 읽은 generation 포함), 쿠폰 예약 작업({@code PENDING})을
     * 한 트랜잭션에 저장한다. 쿠폰 예약 결과가 저장되기 전에는 HP 보류 작업을 등록하지 않는다.
     * 계산 뒤 강의 가격·판매자가 바뀌었으면 409 {@code ORDER_QUOTE_CHANGED}다.
     */
    @Transactional
    public Placement placeWithCoupons(Long buyerUserId, List<Long> lectureIds, OrderCouponQuote quote) {
        List<Long> sortedLectureIds = lectureIds.stream().sorted().toList();
        LocalDateTime now = LocalDateTime.now(clock);
        List<Lecture> lectures = findLectures(sortedLectureIds);
        LectureOrder order = LectureOrder.place(buyerUserId, lectures,
                lectureOrderRepository.findHeldLectureIds(buyerUserId, sortedLectureIds), now);
        quote.requireSameLectures(lectures);
        quote.items().forEach((lectureId, item) -> order.applyItemDiscount(lectureId, item.discountHp(),
                item.platformDiscountHp(), item.sellerDiscountHp()));

        save(order);
        lectureSyncEvents.purchasesChanged(order.getItems());
        for (OrderItem orderItem : order.getItems()) {
            for (CouponCalculateResponse.Coupon coupon : quote.items().get(orderItem.getLectureId()).coupons()) {
                orderItemCouponRepository.save(OrderItemCoupon.create(orderItem.getId(), coupon.memberCouponId(),
                        coupon.couponId(), coupon.couponType(), coupon.applyOrder(), coupon.baseAmount(),
                        coupon.discount(), coupon.platformAmount(), coupon.sellerAmount(),
                        coupon.expectedGeneration(), coupon.policyVersion()));
            }
        }
        orderCouponWorkRepository.save(OrderCouponWork.reserve(order.getId(), now));
        AfterCommit.run(() -> {
            log.info("쿠폰 주문을 만들었다 orderId={}, buyerUserId={}, itemCount={}, totalHp={}, buyerAmountHp={}",
                    order.getId(), buyerUserId, order.getItems().size(),
                    order.getItems().stream().mapToLong(OrderItem::getPriceHp).sum(),
                    order.getItems().stream().mapToLong(OrderItem::buyerAmountHp).sum());
        });
        return new Placement(order.getId(), List.of());
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

    /**
     * 주문 결과. 결제 실패면 묶여 남은 HP가 없음이 확인된 것이라 잔액 부족으로 응답한다 (D58 (4), D69 (1)).
     * 보류가 탈퇴로 닫힌 지갑 때문에 거절됐으면 그 사유로 응답한다 (D76)
     */
    @Transactional(readOnly = true)
    public OrderResponse result(Long buyerUserId, Long orderId) {
        LectureOrder order = lectureOrderSupport.findOrder(buyerUserId, orderId);
        if (order.getStatus() == LectureOrderStatus.HOLD_FAILED) {
            throwCouponFailure(order.getId());
            throw new LectureException(holdFailureCode(order.getId()));
        }
        return lectureOrderSupport.toResponse(order);
    }

    /**
     * 쿠폰 주문이 보류 전에 결제 실패로 끝났으면 그 사유로 응답한다. 쿠폰 예약이 거절됐으면 쿠폰 오류 코드와 함께
     * {@code COUPON_RESERVATION_REJECTED}이고, 예약은 됐지만 보류를 하나도 등록하지 않았으면(예약 응답이 할인 스냅샷과 어긋남)
     * 구매자가 다시 계산해야 하므로 {@code ORDER_QUOTE_CHANGED}다.
     */
    private void throwCouponFailure(Long orderId) {
        Optional<OrderCouponWork> reserve = orderCouponWorkRepository.findByOrderIdAndKind(orderId,
                OrderCouponWorkKind.RESERVE);
        if (reserve.isEmpty()) {
            return;
        }
        if (reserve.get().getStatus() == OrderCouponWorkStatus.REJECTED) {
            throw new CouponRejectedException(LectureErrorCode.COUPON_RESERVATION_REJECTED,
                    reserve.get().rejectedCouponCode(), reserve.get().rejectedMemberCouponId());
        }
        boolean noHold = lectureHpTaskRepository.findByOrderIdIn(List.of(orderId)).stream()
                .noneMatch(task -> task.getPurpose() == LectureHpTaskPurpose.ORDER_HOLD);
        if (noHold) {
            throw new LectureException(LectureErrorCode.ORDER_QUOTE_CHANGED);
        }
    }

    // 결제 실패 사유. 주문에 저장하지 않고 보류 작업의 확정 거절 기록에서 읽는다
    private LectureErrorCode holdFailureCode(Long orderId) {
        boolean walletClosed = lectureHpTaskAttemptRepository.existsByOrderAndResult(orderId, LectureHpTaskType.HOLD,
                LectureHpAttemptResult.TERMINAL_REJECTION, BillingFailureCode.WALLET_CLOSED.name());
        return walletClosed ? LectureErrorCode.WALLET_CLOSED : LectureErrorCode.INSUFFICIENT_BALANCE;
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
