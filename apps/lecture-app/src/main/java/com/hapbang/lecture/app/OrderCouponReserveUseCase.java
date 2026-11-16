package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureOrderProperties;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderCouponReservation;
import com.hapbang.lecture.domain.OrderCouponWork;
import com.hapbang.lecture.domain.OrderCouponWorkKind;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.OrderCouponWorkRepository;
import com.hapbang.lecture.out.OrderItemCouponRepository;
import com.hapbang.lecture.out.coupon.CouponApiClient;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 주문 2~3단계: 저장한 계산 근거로 쿠폰을 예약하고, 결과를 강의 트랜잭션에 기록한다. 트랜잭션 밖에서 부른다.
 * <ol>
 *     <li>짧은 트랜잭션에서 예약 작업에 시도를 기록하고(다음 시도 시각을 미룸) 예약 요청을 만든다. 작업이 이미 끝났으면 보내지 않는다.
 *     다음 시도 시각 전이면(다른 실행이 시도 중) 아무것도 하지 않는다. 시도 한도에 닿았으면 보내지 않고 {@code DEAD}로 멈춘다.
 *     결과를 모르는 예약은 시간만으로 해제하지 않는다.</li>
 *     <li>트랜잭션 밖에서 같은 workId({@code lecture-order:<주문 ID>:reserve})로 예약을 보낸다.</li>
 *     <li>주문 행을 잠근 트랜잭션에서 결과를 기록한다. 그 사이 다른 실행이 작업을 끝냈으면 아무것도 바꾸지 않는다.
 *     <ul>
 *         <li>성공: 응답을 할인 스냅샷과 대조해 예약 generation과 작업 {@code RESERVED}를 남기고, 구매자 금액이 0보다 큰 항목만
 *         보류 작업을 등록한다. 모든 항목이 0 HP면 이 트랜잭션에서 바로 결제 완료다. 쿠폰 주문은 예약 결과가 저장되기 전에 보류 작업이 없다.
 *         대조가 어긋나면 작업은 {@code RESERVED}(쿠폰은 예약됨)로 두고 주문을 결제 실패로 끝내며 같은 트랜잭션에서 해제 작업을 만든다.</li>
 *         <li>거절(4xx): 작업 {@code REJECTED}(쿠폰 오류 코드)와 주문 결제 실패. HP 작업은 없다.
 *         다만 401·403은 내부 API 키·설정 문제라 결과 불명처럼 작업을 {@code PENDING}으로 둔다.</li>
 *         <li>보내지 않음·결과 불명: 작업을 {@code PENDING}으로 두고 주문은 결제 처리 중이다. 다음 시도 시각이 지나면 다시 보낸다.</li>
 *     </ul>
 *     </li>
 * </ol>
 */
@Slf4j
@Service
public class OrderCouponReserveUseCase {

    /** 예약 응답이 할인 스냅샷과 어긋났을 때 붙이는 alert 표식. 쿠폰이 예약 때 대조하므로 정상이면 생기지 않는다 */
    static final String RESERVATION_MISMATCH_LOG = "[LECTURE_COUPON_RESERVATION_MISMATCH]";
    /** 쿠폰 예약·해제 작업의 자동 처리가 멈췄을 때 붙이는 alert 표식 */
    static final String WORK_DEAD_LOG = "[LECTURE_COUPON_WORK_DEAD]";

    private final LectureOrderRepository lectureOrderRepository;
    private final OrderItemCouponRepository orderItemCouponRepository;
    private final OrderCouponWorkRepository orderCouponWorkRepository;
    private final LectureHpTaskSupport lectureHpTaskSupport;
    private final LectureSyncEvents lectureSyncEvents;
    private final LectureOrderEvents lectureOrderEvents;
    private final OrderCouponReleaseRegistration orderCouponReleaseRegistration;
    private final OrderHoldSettlement orderHoldSettlement;
    private final CouponApiClient couponApiClient;
    private final LectureOrderProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderCouponReserveUseCase(LectureOrderRepository lectureOrderRepository,
                                     OrderItemCouponRepository orderItemCouponRepository,
                                     OrderCouponWorkRepository orderCouponWorkRepository,
                                     LectureHpTaskSupport lectureHpTaskSupport,
                                     LectureSyncEvents lectureSyncEvents,
                                     LectureOrderEvents lectureOrderEvents,
                                     OrderCouponReleaseRegistration orderCouponReleaseRegistration,
                                     OrderHoldSettlement orderHoldSettlement,
                                     CouponApiClient couponApiClient,
                                     LectureOrderProperties properties,
                                     PlatformTransactionManager transactionManager,
                                     @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderRepository = lectureOrderRepository;
        this.orderItemCouponRepository = orderItemCouponRepository;
        this.orderCouponWorkRepository = orderCouponWorkRepository;
        this.lectureHpTaskSupport = lectureHpTaskSupport;
        this.lectureSyncEvents = lectureSyncEvents;
        this.lectureOrderEvents = lectureOrderEvents;
        this.orderCouponReleaseRegistration = orderCouponReleaseRegistration;
        this.orderHoldSettlement = orderHoldSettlement;
        this.couponApiClient = couponApiClient;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 주문의 쿠폰 예약을 한 번 보내고 결과를 기록한다.
     *
     * @return 이번에 등록한 보류 작업 ID(항목 순서). 예약이 끝나지 않았거나 다른 실행이 처리했으면 빈 목록
     */
    public List<Long> reserve(Long orderId) {
        try {
            CouponReserveRequest request = transaction.execute(status -> prepare(orderId));
            if (request == null) {
                return List.of();
            }
            CouponCallResult<CouponReserveResponse> result = couponApiClient.reserve(request);
            return transaction.execute(status -> apply(orderId, result));
        } catch (ObjectOptimisticLockingFailureException concurrent) {
            // 같은 작업을 다른 실행(재시도 배치)이 동시에 바꿨다. 그쪽 결과를 따른다
            log.info("쿠폰 예약 작업을 다른 실행이 처리 중이라 이번 시도를 멈춘다 orderId={}", orderId);
            return List.of();
        }
    }

    private CouponReserveRequest prepare(Long orderId) {
        OrderCouponWork work = reserveWork(orderId);
        if (!work.isPending()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!work.isDue(now)) {
            // 다른 실행(주문 요청이나 겹친 배치)이 이미 시도를 기록하고 결과를 기다리는 중이다. 그 시도가 끝나 다음 시도 시각이
            // 지나기 전에는 다시 보내지도, 시도 한도로 멈추지도 않는다
            log.debug("쿠폰 예약 작업이 아직 보낼 차례가 아니다 orderId={}, nextAttemptAt={}", orderId, work.getNextAttemptAt());
            return null;
        }
        if (!work.hasAttemptsLeft(properties.couponWorkMaxAttempts())) {
            // 결과를 모르는 예약은 시간만으로 해제하지 않는다. 주문은 결제 처리 중으로 남고 운영자가 쿠폰 예약 상태를 확인한다
            work.markDead(now);
            log.error(WORK_DEAD_LOG + " 쿠폰 예약 작업이 결과를 모른 채 시도 한도에 닿아 자동 처리를 멈췄다. 주문은 결제 처리 중으로 "
                    + "남는다. 운영자가 쿠폰 예약 상태를 확인한다 orderId={}, workId={}, attemptCount={}", orderId,
                    work.getWorkId(), work.getAttemptCount());
            return null;
        }
        LectureOrder order = lectureOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("쿠폰 예약 작업의 주문이 없습니다. orderId=" + orderId));
        work.startAttempt(now, properties.couponWorkRetryAfter());
        orderCouponWorkRepository.saveAndFlush(work);
        Map<Long, List<OrderItemCoupon>> coupons = couponsByItem(order);
        List<CouponReserveRequest.Item> items = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getId))
                .map(item -> new CouponReserveRequest.Item(item.getId(), item.getLectureId(), item.getSellerUserId(),
                        item.getPriceHp(), coupons.getOrDefault(item.getId(), List.of()).stream()
                        .map(coupon -> new CouponReserveRequest.Coupon(coupon.getMemberCouponId(),
                                coupon.getExpectedGeneration(), coupon.getPolicyVersion(), coupon.getApplyOrder(),
                                coupon.getDiscountHp(), coupon.getPlatformAmountHp(), coupon.getSellerAmountHp()))
                        .toList()))
                .toList();
        return new CouponReserveRequest(order.getBuyerUserId(), orderId, work.getWorkId(), items);
    }

    private List<Long> apply(Long orderId, CouponCallResult<CouponReserveResponse> result) {
        LectureOrder order = lectureOrderRepository.findForUpdateById(orderId)
                .orElseThrow(() -> new IllegalStateException("쿠폰 예약 작업의 주문이 없습니다. orderId=" + orderId));
        OrderCouponWork work = reserveWork(orderId);
        if (!work.isPending() || order.getStatus() != LectureOrderStatus.HOLD_PENDING) {
            log.debug("쿠폰 예약 결과를 기록하지 않는다. 이미 끝난 작업이다 orderId={}, workStatus={}, orderStatus={}", orderId,
                    work.getStatus(), order.getStatus());
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return switch (result) {
            case CouponCallResult.Succeeded<CouponReserveResponse> succeeded ->
                    applyReserved(order, work, succeeded.data(), now);
            case CouponCallResult.Rejected<CouponReserveResponse> rejected
                    when OrderCouponQuoteUseCase.isAuthFailure(rejected.status()) -> {
                log.error(OrderCouponQuoteUseCase.AUTH_FAILED_LOG + " 쿠폰 내부 API가 예약의 인증·권한을 거절했다. "
                        + "내부 API 키·설정을 확인한다. 작업은 다시 보낸다 orderId={}, workId={}, httpStatus={}", orderId,
                        work.getWorkId(), rejected.status());
                yield List.of();
            }
            case CouponCallResult.Rejected<CouponReserveResponse> rejected -> {
                String couponCode = rejected.code() == null ? "HTTP_" + rejected.status() : rejected.code();
                work.markRejected(couponCode, rejected.memberCouponId(), now);
                order.markHoldFailed();
                lectureSyncEvents.purchasesChanged(order.getItems());
                lectureOrderEvents.holdFailed(order, now);
                log.info("쿠폰 예약이 거절되어 주문을 결제 실패로 끝냈다 orderId={}, workId={}, httpStatus={}, couponCode={}, "
                                + "memberCouponId={}", orderId, work.getWorkId(), rejected.status(), couponCode,
                        rejected.memberCouponId());
                yield List.of();
            }
            case CouponCallResult.NotSent<CouponReserveResponse> notSent -> {
                log.warn("쿠폰 예약을 보내지 못해 다음 시도 시각에 다시 보낸다 orderId={}, workId={}, attemptCount={}, reason={}",
                        orderId, work.getWorkId(), work.getAttemptCount(), notSent.reason());
                yield List.of();
            }
            case CouponCallResult.Unknown<CouponReserveResponse> unknown -> {
                log.warn("쿠폰 예약 결과를 몰라 다음 시도 시각에 같은 내용으로 다시 보낸다 orderId={}, workId={}, attemptCount={}, "
                                + "httpStatus={}, reason={}", orderId, work.getWorkId(), work.getAttemptCount(),
                        unknown.status(), unknown.reason());
                yield List.of();
            }
        };
    }

    private List<Long> applyReserved(LectureOrder order, OrderCouponWork work, CouponReserveResponse response,
                                     LocalDateTime now) {
        List<OrderItemCoupon> coupons = orderItemCouponRepository.findByOrderItemIdInOrderByOrderItemIdAscApplyOrderAsc(
                order.getItems().stream().map(OrderItem::getId).toList());
        recordReservedGenerations(coupons, response);
        work.markReserved(now);
        Optional<String> mismatch = OrderCouponReservation.mismatch(order.getItems(), coupons, response);
        if (mismatch.isPresent()) {
            // 쿠폰은 예약된 채라 같은 트랜잭션에서 해제 작업을 만든다
            order.markHoldFailed();
            lectureSyncEvents.purchasesChanged(order.getItems());
            lectureOrderEvents.holdFailed(order, now);
            orderCouponReleaseRegistration.registerIfReserved(order, now);
            log.error(RESERVATION_MISMATCH_LOG + " 쿠폰 예약 응답이 주문의 할인 스냅샷과 달라 주문을 결제 실패로 끝냈다 orderId={}, "
                    + "workId={}, mismatch={}", order.getId(), work.getWorkId(), mismatch.get());
            return List.of();
        }
        // 구매자 금액이 0인 항목은 보류할 HP가 없어 보류를 성공으로 센다
        List<Long> holdTaskIds = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getId))
                .filter(OrderItem::requiresBuyerHpTask)
                .map(item -> lectureHpTaskSupport.register(OrderCreateUseCase.holdTask(order, item, now)).getId())
                .toList();
        // 모든 항목이 0 HP면 이 트랜잭션에서 바로 결제 완료다
        orderHoldSettlement.settleRegistered(order);
        log.info("쿠폰을 예약하고 보류 작업을 등록했다 orderId={}, workId={}, holdCount={}", order.getId(), work.getWorkId(),
                holdTaskIds.size());
        return holdTaskIds;
    }

    /** 응답의 사용 시도마다 예약 generation을 남긴다. 해제는 이 값으로 하므로 대조가 어긋나도 남긴다 */
    private static void recordReservedGenerations(List<OrderItemCoupon> coupons, CouponReserveResponse response) {
        Map<String, OrderItemCoupon> byKey = coupons.stream()
                .collect(Collectors.toMap(coupon -> coupon.getOrderItemId() + ":" + coupon.getMemberCouponId(),
                        Function.identity()));
        response.items().forEach(item -> item.coupons().forEach(coupon -> {
            OrderItemCoupon saved = byKey.get(item.orderItemId() + ":" + coupon.memberCouponId());
            if (saved != null) {
                saved.recordReserved(coupon.reservedGeneration());
            }
        }));
    }

    private Map<Long, List<OrderItemCoupon>> couponsByItem(LectureOrder order) {
        return orderItemCouponRepository.findByOrderItemIdInOrderByOrderItemIdAscApplyOrderAsc(
                        order.getItems().stream().map(OrderItem::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(OrderItemCoupon::getOrderItemId));
    }

    private OrderCouponWork reserveWork(Long orderId) {
        return orderCouponWorkRepository.findByOrderIdAndKind(orderId, OrderCouponWorkKind.RESERVE)
                .orElseThrow(() -> new IllegalStateException("쿠폰 예약 작업이 없습니다. orderId=" + orderId));
    }
}
