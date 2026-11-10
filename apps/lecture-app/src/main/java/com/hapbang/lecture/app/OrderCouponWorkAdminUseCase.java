package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.CouponReservationOutcome;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderCouponWork;
import com.hapbang.lecture.domain.OrderCouponWorkKind;
import com.hapbang.lecture.domain.OrderCouponWorkStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.OrderCouponWorkRepository;
import com.hapbang.lecture.out.OrderItemCouponRepository;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.lecture.out.coupon.CouponClient;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 관리자의 쿠폰 작업 조치. 자동 처리가 멈춘({@code DEAD}) 예약·해제 작업을 운영자가 끝낸다. 트랜잭션 밖에서 부른다
 * (쿠폰 예약 상태 조회는 트랜잭션 밖, 기록은 주문 행을 잠근 짧은 트랜잭션).
 * <ul>
 *     <li>{@link #retry 다시 시도}: {@code DEAD} → {@code PENDING}(시도 0, 다음 시도 지금). 재전송 배치가 같은 작업 ID로 다시 보낸다.
 *     예약은 주문이 결제 처리 중, 해제는 결제 실패일 때만.</li>
 *     <li>{@link #fail 결제 실패 확정}(예약 작업만, 주문 결제 처리 중): 쿠폰 예약 상태를 조회해 판정한다
 *     ({@link CouponReservationOutcome}).
 *     예약이 남아 있으면 예약 generation을 남기고 작업 {@code RESERVED}·주문 결제 실패·해제 작업을 만들고 해제를 한 번 보낸다.
 *     거절이면 작업 {@code REJECTED}·주문 결제 실패. 예약이 없으면 작업은 {@code DEAD} 그대로 두고 주문만 결제 실패.
 *     결제 완료·취소가 보이면 409, 조회할 수 없으면 503이고 아무것도 바꾸지 않는다. 시간만으로 결제 실패를 확정하지 않는다.</li>
 *     <li>{@link #resolve 해제 확인 완료}(해제 작업만): 쿠폰 쪽에 예약이 남지 않았음이 확인되면 {@code RELEASED}로 닫는다.
 *     남아 있으면 409(다시 시도로 해제를 보낸다).</li>
 * </ul>
 * 같은 작업을 동시에 바꿔 버전 충돌이 나면 409 {@code COUPON_WORK_CHANGED}다. 상태 변경은 관리자 ID·작업 ID·전후 상태를 로그로 남긴다.
 */
@Slf4j
@Service
public class OrderCouponWorkAdminUseCase {

    /** 쿠폰 예약 상태를 조회하지 못했을 때 상세 조회가 주는 상태 */
    public static final String COUPON_STATUS_UNAVAILABLE = "UNAVAILABLE";
    /** 결제 완료·취소된 사용 시도가 보여 조치를 멈췄을 때 붙이는 alert 표식. 자동으로 고치지 않는다 */
    static final String RESERVATION_SETTLED_LOG = "[LECTURE_COUPON_WORK_RESERVATION_SETTLED]";
    /** 쿠폰이 아직 예약해 둔 사용 시도 상태 */
    private static final String RESERVED_USAGE = "RESERVED";

    private final OrderCouponWorkRepository orderCouponWorkRepository;
    private final OrderItemCouponRepository orderItemCouponRepository;
    private final LectureOrderRepository lectureOrderRepository;
    private final OrderCouponReleaseRegistration orderCouponReleaseRegistration;
    private final OrderCouponReleaseUseCase orderCouponReleaseUseCase;
    private final LectureSyncEvents lectureSyncEvents;
    private final CouponClient couponClient;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderCouponWorkAdminUseCase(OrderCouponWorkRepository orderCouponWorkRepository,
                                       OrderItemCouponRepository orderItemCouponRepository,
                                       LectureOrderRepository lectureOrderRepository,
                                       OrderCouponReleaseRegistration orderCouponReleaseRegistration,
                                       OrderCouponReleaseUseCase orderCouponReleaseUseCase,
                                       LectureSyncEvents lectureSyncEvents,
                                       CouponClient couponClient,
                                       PlatformTransactionManager transactionManager,
                                       @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.orderCouponWorkRepository = orderCouponWorkRepository;
        this.orderItemCouponRepository = orderItemCouponRepository;
        this.lectureOrderRepository = lectureOrderRepository;
        this.orderCouponReleaseRegistration = orderCouponReleaseRegistration;
        this.orderCouponReleaseUseCase = orderCouponReleaseUseCase;
        this.lectureSyncEvents = lectureSyncEvents;
        this.couponClient = couponClient;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 결제 실패 확정의 결과 */
    /** 결제 실패 확정에서 쿠폰 쪽 예약이 없어 관리자가 닫은 예약 작업의 거절 코드 */
    public static final String ADMIN_FAILED_NOT_RESERVED = "ADMIN_FAILED_NOT_RESERVED";

    public enum FailOutcome {
        /** 쿠폰 쪽 예약이 있어 해제 작업을 만들고 해제를 한 번 보냈다 */
        RESERVED_RELEASING,
        /** 쿠폰이 예약을 거절했었다 */
        REJECTED,
        /** 쿠폰 쪽 예약이 없다(기록 없음·모두 해제). 예약 작업은 {@link #ADMIN_FAILED_NOT_RESERVED}로 닫는다 */
        NOT_RESERVED
    }

    /**
     * 상세 조회용 쿠폰 예약 상태. 이 주문의 예약 작업 ID로 조회한다(해제 작업도 같다).
     *
     * @param couponStatus 쿠폰 응답의 최상위 상태. 조회하지 못했으면 {@value #COUPON_STATUS_UNAVAILABLE}
     * @param reservation  쿠폰 응답. 조회하지 못했으면 null
     */
    public record CouponReservationView(String couponStatus, CouponReservationStatusResponse reservation) {
    }

    /**
     * 상세 조회용 작업·주문 상태·주문 항목별 쿠폰 계산 근거(짧은 읽기 트랜잭션).
     *
     * @param coupons 주문 항목 ID, 항목 안 적용 순서대로
     */
    public record WorkDetail(OrderCouponWork work, LectureOrderStatus orderStatus, List<OrderItemCoupon> coupons) {
    }

    /** 작업과 주문 상태, 주문 항목별 쿠폰 계산 근거를 읽는다. 쿠폰은 부르지 않는다 */
    public WorkDetail detail(String workId) {
        return transaction.execute(status -> {
            OrderCouponWork work = findWork(workId);
            LectureOrder order = findOrder(work.getOrderId());
            return new WorkDetail(work, order.getStatus(),
                    orderItemCouponRepository.findByOrderItemIdInOrderByOrderItemIdAscApplyOrderAsc(
                            order.getItems().stream().map(OrderItem::getId).toList()));
        });
    }

    /** 이 작업 주문의 쿠폰 예약 상태. 쿠폰을 부르지 못해도 실패시키지 않고 {@value #COUPON_STATUS_UNAVAILABLE}로 준다 */
    public CouponReservationView reservationStatus(String workId) {
        Target target = read(workId);
        CouponCallResult<CouponReservationStatusResponse> result = queryReservation(target);
        if (result instanceof CouponCallResult.Succeeded<CouponReservationStatusResponse> succeeded) {
            return new CouponReservationView(succeeded.data().status(), succeeded.data());
        }
        return new CouponReservationView(COUPON_STATUS_UNAVAILABLE, null);
    }

    /** 다시 시도: {@code DEAD} → {@code PENDING}. 예약은 주문 결제 처리 중, 해제는 주문 결제 실패일 때만 */
    public void retry(Long adminUserId, String workId) {
        Target target = read(workId);
        inTransaction(() -> {
            LocalDateTime now = LocalDateTime.now(clock);
            Locked locked = lock(target, workId);
            requireOrderStatus(locked.order(), locked.work().getKind() == OrderCouponWorkKind.RESERVE
                    ? LectureOrderStatus.HOLD_PENDING : LectureOrderStatus.HOLD_FAILED);
            OrderCouponWorkStatus before = locked.work().getStatus();
            locked.work().retryByAdmin(now);
            logChange("다시 시도", adminUserId, locked.work(), before);
        });
    }

    /** 결제 실패 확정(예약 작업, 주문 결제 처리 중). 쿠폰 예약 상태를 트랜잭션 밖에서 조회해 판정한다 */
    public FailOutcome fail(Long adminUserId, String workId) {
        Target target = read(workId);
        target.work().requireDeadOf(OrderCouponWorkKind.RESERVE);
        requireOrderStatus(target.orderStatus(), LectureOrderStatus.HOLD_PENDING);
        CouponReservationStatusResponse reservation = requireReservation(target, workId);
        CouponReservationOutcome outcome = requireSettledFree(CouponReservationOutcome.of(reservation), adminUserId,
                workId, reservation);

        FailOutcome result = inTransaction(() -> {
            LocalDateTime now = LocalDateTime.now(clock);
            Locked locked = lock(target, workId);
            locked.work().requireDeadOf(OrderCouponWorkKind.RESERVE);
            requireOrderStatus(locked.order(), LectureOrderStatus.HOLD_PENDING);
            OrderCouponWorkStatus before = locked.work().getStatus();
            FailOutcome recorded = switch (outcome) {
                case RESERVED -> {
                    recordReservedGenerations(locked.order(), reservation, adminUserId, workId);
                    locked.work().confirmReservedByAdmin(now);
                    yield FailOutcome.RESERVED_RELEASING;
                }
                case REJECTED -> {
                    locked.work().confirmRejectedByAdmin(reservation.rejectCode() == null ? "UNKNOWN"
                            : reservation.rejectCode(), now);
                    yield FailOutcome.REJECTED;
                }
                case NOT_RESERVED -> {
                    // 쿠폰 쪽 예약이 없다. DEAD로 두면 닫을 길이 없어 DEAD 목록·alert에 남으므로 거절로 닫는다
                    locked.work().confirmRejectedByAdmin(ADMIN_FAILED_NOT_RESERVED, now);
                    yield FailOutcome.NOT_RESERVED;
                }
                case CONFLICT -> throw new IllegalStateException("위에서 막았다");
            };
            locked.order().markHoldFailed();
            lectureSyncEvents.purchasesChanged(locked.order().getItems());
            if (recorded == FailOutcome.RESERVED_RELEASING) {
                orderCouponReleaseRegistration.registerIfReserved(locked.order(), now);
            }
            logChange("결제 실패 확정(" + recorded + ")", adminUserId, locked.work(), before);
            return recorded;
        });
        if (result == FailOutcome.RESERVED_RELEASING) {
            // 예약한 쿠폰을 바로 한 번 돌려준다. 결과를 모르면 재전송 배치가 잇는다. 결제 실패는 이미 확정됐으므로 여기서 예외가
            // 나도 관리자 응답은 확정 결과 그대로이고, 해제 작업은 재전송 배치가 잇는다
            try {
                orderCouponReleaseUseCase.release(target.orderId());
            } catch (RuntimeException unexpected) {
                log.error(OrderCouponWorkUseCase.RETRY_FAILED_LOG + " 결제 실패 확정 뒤 쿠폰 해제를 바로 보내지 못했다. 재전송 배치가 잇는다 workId={}, orderId={}", workId,
                        target.orderId(), unexpected);
            }
        }
        return result;
    }

    /** 해제 확인 완료(해제 작업). 쿠폰 쪽에 이 주문의 예약이 남아 있지 않을 때만 {@code RELEASED}로 닫는다 */
    public void resolve(Long adminUserId, String workId) {
        Target target = read(workId);
        target.work().requireDeadOf(OrderCouponWorkKind.RELEASE);
        requireOrderStatus(target.orderStatus(), LectureOrderStatus.HOLD_FAILED);
        CouponReservationStatusResponse reservation = requireReservation(target, workId);
        CouponReservationOutcome outcome = requireSettledFree(CouponReservationOutcome.of(reservation), adminUserId,
                workId, reservation);
        if (outcome == CouponReservationOutcome.RESERVED) {
            throw new LectureException(LectureErrorCode.COUPON_RESERVATION_STILL_HELD);
        }
        inTransaction(() -> {
            Locked locked = lock(target, workId);
            OrderCouponWorkStatus before = locked.work().getStatus();
            locked.work().confirmReleasedByAdmin(LocalDateTime.now(clock));
            logChange("해제 확인 완료", adminUserId, locked.work(), before);
        });
    }

    private CouponReservationStatusResponse requireReservation(Target target, String workId) {
        CouponCallResult<CouponReservationStatusResponse> result = queryReservation(target);
        if (result instanceof CouponCallResult.Succeeded<CouponReservationStatusResponse> succeeded) {
            return succeeded.data();
        }
        if (result instanceof CouponCallResult.Rejected<CouponReservationStatusResponse> rejected
                && OrderCouponQuoteUseCase.isAuthFailure(rejected.status())) {
            log.error(OrderCouponQuoteUseCase.AUTH_FAILED_LOG + " 쿠폰 내부 API가 예약 상태 조회의 인증·권한을 거절했다. "
                    + "내부 API 키·설정을 확인한다 workId={}, httpStatus={}", workId, rejected.status());
        } else {
            log.warn("쿠폰 예약 상태를 조회하지 못해 쿠폰 작업 조치를 하지 않았다 workId={}, result={}", workId, result);
        }
        throw new LectureException(LectureErrorCode.COUPON_UNAVAILABLE,
                "쿠폰 예약 상태를 확인할 수 없어 아무것도 바꾸지 않았습니다. 잠시 후 다시 시도해 주세요.");
    }

    /** 결제 완료·취소된 사용 시도가 보이면 자동으로 고치지 않는다. 아무것도 바꾸지 않고 409다 */
    private static CouponReservationOutcome requireSettledFree(CouponReservationOutcome outcome, Long adminUserId,
                                                               String workId,
                                                               CouponReservationStatusResponse reservation) {
        if (outcome == CouponReservationOutcome.CONFLICT) {
            log.error(RESERVATION_SETTLED_LOG + " 결제 완료·취소된 쿠폰 사용 시도가 보여 쿠폰 작업 조치를 멈췄다. 운영자가 쿠폰·주문을 "
                    + "확인한다 adminUserId={}, workId={}, reservation={}", adminUserId, workId, reservation);
            throw new LectureException(LectureErrorCode.COUPON_RESERVATION_SETTLED);
        }
        return outcome;
    }

    /**
     * 쿠폰이 아직 예약해 둔({@code RESERVED}) 사용 시도마다 예약 generation을 남긴다(해제는 이 값으로 한다). 이미 해제된 사용 시도는
     * 해제 요청에 넣으면 거절되므로 남기지 않는다. 계산 근거에 없는 예약 사용 시도가 있으면 정확히 해제할 수 없으므로 바꾸지 않고 409다
     */
    private void recordReservedGenerations(LectureOrder order, CouponReservationStatusResponse reservation,
                                           Long adminUserId, String workId) {
        Map<String, OrderItemCoupon> coupons = orderItemCouponRepository
                .findByOrderItemIdInOrderByOrderItemIdAscApplyOrderAsc(
                        order.getItems().stream().map(OrderItem::getId).toList())
                .stream()
                .collect(Collectors.toMap(coupon -> coupon.getOrderItemId() + ":" + coupon.getMemberCouponId(),
                        Function.identity()));
        for (CouponReservationStatusResponse.Item item : reservation.items()) {
            for (CouponReservationStatusResponse.Coupon coupon : item.coupons()) {
                if (!RESERVED_USAGE.equals(coupon.status())) {
                    continue;
                }
                OrderItemCoupon saved = coupons.get(item.orderItemId() + ":" + coupon.memberCouponId());
                if (saved == null) {
                    log.error(RESERVATION_SETTLED_LOG + " 쿠폰 예약에 주문의 계산 근거에 없는 사용 시도가 있어 결제 실패 확정을 멈췄다 "
                                    + "adminUserId={}, workId={}, orderItemId={}, memberCouponId={}", adminUserId, workId,
                            item.orderItemId(), coupon.memberCouponId());
                    throw new LectureException(LectureErrorCode.COUPON_RESERVATION_SETTLED,
                            "쿠폰 예약에 주문의 계산 근거에 없는 사용 시도가 있어 멈췄습니다. 쿠폰 사용 시도와 주문을 직접 확인해 주세요.");
                }
                saved.recordReserved(coupon.reservedGeneration());
            }
        }
    }

    private CouponCallResult<CouponReservationStatusResponse> queryReservation(Target target) {
        return couponClient.reservationStatus(target.reserveWorkId(), target.buyerUserId(), target.orderId());
    }

    /** 조치 전 확인용으로 작업과 주문을 읽는다(짧은 읽기 트랜잭션) */
    private Target read(String workId) {
        return transaction.execute(status -> {
            OrderCouponWork work = findWork(workId);
            LectureOrder order = findOrder(work.getOrderId());
            String reserveWorkId = orderCouponWorkRepository.findByOrderIdAndKind(order.getId(),
                    OrderCouponWorkKind.RESERVE).map(OrderCouponWork::getWorkId).orElseThrow(() ->
                    new IllegalStateException("쿠폰 작업 주문에 예약 작업이 없습니다. orderId=" + order.getId()));
            return new Target(work, order.getId(), order.getBuyerUserId(), order.getStatus(), reserveWorkId);
        });
    }

    /**
     * 확인 때 읽은 주문 ID로 주문 행을 먼저 잠그고(다른 쿠폰 작업 기록과 같은 순서), 그 뒤 이 트랜잭션에서 작업을 처음 읽는다.
     * 잠근 뒤의 첫 조회라 잠금을 기다리는 사이 커밋된 다른 조치(다시 시도 등)까지 본 최신 상태다. 작업의 주문은 바뀌지 않는다
     */
    private Locked lock(Target target, String workId) {
        LectureOrder order = lectureOrderRepository.findForUpdateById(target.orderId())
                .orElseThrow(() -> new IllegalStateException("쿠폰 작업의 주문이 없습니다. workId=" + workId));
        OrderCouponWork work = findWork(workId);
        if (!work.getOrderId().equals(target.orderId())) {
            throw new IllegalStateException("쿠폰 작업의 주문이 바뀌었습니다. workId=" + workId + ", orderId="
                    + work.getOrderId() + ", expected=" + target.orderId());
        }
        // 조회한 뒤 무엇이든 바뀌었으면(다른 관리자의 조치, 다시 시도 뒤 재전송을 거쳐 다시 DEAD 등) 그때 본 쿠폰 상태로 기록하지 않는다
        if (work.getVersion() != target.work().getVersion()) {
            throw new LectureException(LectureErrorCode.COUPON_WORK_CHANGED);
        }
        return new Locked(work, order);
    }

    private OrderCouponWork findWork(String workId) {
        return orderCouponWorkRepository.findByWorkId(workId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.COUPON_WORK_NOT_FOUND));
    }

    private LectureOrder findOrder(Long orderId) {
        return lectureOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("쿠폰 작업의 주문이 없습니다. orderId=" + orderId));
    }

    private static void requireOrderStatus(LectureOrder order, LectureOrderStatus expected) {
        requireOrderStatus(order.getStatus(), expected);
    }

    private static void requireOrderStatus(LectureOrderStatus actual, LectureOrderStatus expected) {
        if (actual != expected) {
            throw new LectureException(LectureErrorCode.COUPON_WORK_ORDER_STATE_MISMATCH,
                    "주문이 " + expected + " 상태가 아닙니다(지금 " + actual + ").");
        }
    }

    private void inTransaction(Runnable action) {
        inTransaction(() -> {
            action.run();
            return null;
        });
    }

    /** 같은 작업을 동시에 바꿨으면(버전 충돌) 409로 바꾼다 */
    private <T> T inTransaction(Supplier<T> action) {
        try {
            return transaction.execute(status -> action.get());
        } catch (ObjectOptimisticLockingFailureException concurrent) {
            throw new LectureException(LectureErrorCode.COUPON_WORK_CHANGED);
        }
    }

    private static void logChange(String action, Long adminUserId, OrderCouponWork work, OrderCouponWorkStatus before) {
        AfterCommit.run(() -> log.info("관리자가 쿠폰 작업을 처리했다 action={}, adminUserId={}, workId={}, orderId={}, "
                        + "before={}, after={}", action, adminUserId, work.getWorkId(), work.getOrderId(), before,
                work.getStatus()));
    }

    private record Target(OrderCouponWork work, Long orderId, Long buyerUserId, LectureOrderStatus orderStatus,
                          String reserveWorkId) {
    }

    private record Locked(OrderCouponWork work, LectureOrder order) {
    }
}
