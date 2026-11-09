package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureOrderProperties;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderCouponWork;
import com.hapbang.lecture.domain.OrderCouponWorkKind;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.OrderCouponWorkRepository;
import com.hapbang.lecture.out.OrderItemCouponRepository;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.lecture.out.coupon.CouponClient;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 결제 실패한 주문의 쿠폰 해제를 한 번 보내고 결과를 기록한다. 트랜잭션 밖에서 부른다. 순서는 예약({@link OrderCouponReserveUseCase})과 같다.
 * <ol>
 *     <li>짧은 트랜잭션에서 해제 작업에 시도를 기록하고, 남긴 예약 generation으로 해제 요청을 만든다. 같은 작업은 늘 같은 내용이다.
 *     시도 한도에 닿았으면 보내지 않고 {@code DEAD}로 멈춘다.</li>
 *     <li>트랜잭션 밖에서 같은 workId로 해제를 보낸다.</li>
 *     <li>주문 행을 잠근 트랜잭션에서 결과를 기록한다. 성공이면 {@code RELEASED}다. 거절은 다시 보내도 같으므로
 *     {@code DEAD}로 멈추고 운영자가 확인한다(401·403은 설정 문제라 {@code PENDING}으로 둔다). 보내지 않음·결과 불명이면
 *     {@code PENDING}으로 두고 다음 시도 시각에 다시 보낸다.</li>
 * </ol>
 * 시간만으로 해제하지 않는다. 결과를 모르는 예약은 해제하지 않고 예약 작업이 끝날 때까지 기다린다.
 */
@Slf4j
@Service
public class OrderCouponReleaseUseCase {

    /** 해제 사유. 같은 workId는 같은 내용이어야 하므로 고정 값이다 */
    static final String RELEASE_REASON = "ORDER_PAYMENT_FAILED";

    private final LectureOrderRepository lectureOrderRepository;
    private final OrderItemCouponRepository orderItemCouponRepository;
    private final OrderCouponWorkRepository orderCouponWorkRepository;
    private final CouponClient couponClient;
    private final LectureOrderProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderCouponReleaseUseCase(LectureOrderRepository lectureOrderRepository,
                                     OrderItemCouponRepository orderItemCouponRepository,
                                     OrderCouponWorkRepository orderCouponWorkRepository,
                                     CouponClient couponClient,
                                     LectureOrderProperties properties,
                                     PlatformTransactionManager transactionManager,
                                     @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderRepository = lectureOrderRepository;
        this.orderItemCouponRepository = orderItemCouponRepository;
        this.orderCouponWorkRepository = orderCouponWorkRepository;
        this.couponClient = couponClient;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 주문에 보낼 해제 작업이 있으면 한 번 보낸다. 없거나 이미 끝났으면 아무것도 하지 않는다 */
    public void release(Long orderId) {
        try {
            CouponReleaseRequest request = transaction.execute(status -> prepare(orderId));
            if (request == null) {
                return;
            }
            CouponCallResult<CouponReleaseResponse> result = couponClient.release(request);
            transaction.executeWithoutResult(status -> apply(orderId, result));
        } catch (ObjectOptimisticLockingFailureException concurrent) {
            log.info("쿠폰 해제 작업을 다른 실행이 처리 중이라 이번 시도를 멈춘다 orderId={}", orderId);
        }
    }

    private CouponReleaseRequest prepare(Long orderId) {
        OrderCouponWork work = orderCouponWorkRepository.findByOrderIdAndKind(orderId, OrderCouponWorkKind.RELEASE)
                .orElse(null);
        if (work == null || !work.isPending()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!work.hasAttemptsLeft(properties.couponWorkMaxAttempts())) {
            work.markDead(now);
            log.error(OrderCouponReserveUseCase.WORK_DEAD_LOG + " 쿠폰 해제 작업이 시도 한도에 닿아 자동 처리를 멈췄다. "
                    + "쿠폰 예약이 남아 있을 수 있어 운영자가 확인한다 orderId={}, workId={}, attemptCount={}", orderId,
                    work.getWorkId(), work.getAttemptCount());
            return null;
        }
        LectureOrder order = lectureOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("쿠폰 해제 작업의 주문이 없습니다. orderId=" + orderId));
        List<CouponReleaseRequest.Usage> usages = orderItemCouponRepository
                .findByOrderItemIdInOrderByOrderItemIdAscApplyOrderAsc(
                        order.getItems().stream().map(OrderItem::getId).toList())
                .stream()
                .filter(coupon -> coupon.getReservedGeneration() != null)
                .sorted(Comparator.comparing(OrderItemCoupon::getMemberCouponId))
                .map(coupon -> new CouponReleaseRequest.Usage(coupon.getMemberCouponId(),
                        coupon.getReservedGeneration()))
                .toList();
        if (usages.isEmpty()) {
            work.markDead(now);
            log.error(OrderCouponReserveUseCase.WORK_DEAD_LOG + " 쿠폰 해제 작업에 해제할 예약 generation이 없어 멈췄다. "
                    + "운영자가 쿠폰 예약을 확인한다 orderId={}, workId={}", orderId, work.getWorkId());
            return null;
        }
        work.startAttempt(now, properties.couponWorkRetryAfter());
        orderCouponWorkRepository.saveAndFlush(work);
        return new CouponReleaseRequest(orderId, work.getWorkId(), usages, RELEASE_REASON, null);
    }

    private void apply(Long orderId, CouponCallResult<CouponReleaseResponse> result) {
        lectureOrderRepository.findForUpdateById(orderId)
                .orElseThrow(() -> new IllegalStateException("쿠폰 해제 작업의 주문이 없습니다. orderId=" + orderId));
        OrderCouponWork work = orderCouponWorkRepository.findByOrderIdAndKind(orderId, OrderCouponWorkKind.RELEASE)
                .orElseThrow(() -> new IllegalStateException("쿠폰 해제 작업이 없습니다. orderId=" + orderId));
        if (!work.isPending()) {
            log.debug("쿠폰 해제 결과를 기록하지 않는다. 이미 끝난 작업이다 orderId={}, workStatus={}", orderId, work.getStatus());
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        switch (result) {
            case CouponCallResult.Succeeded<CouponReleaseResponse> succeeded -> {
                work.markReleased(now);
                log.info("결제 실패한 주문의 쿠폰 예약을 해제했다 orderId={}, workId={}, usageCount={}", orderId,
                        work.getWorkId(), succeeded.data().usages().size());
            }
            case CouponCallResult.Rejected<CouponReleaseResponse> rejected
                    when OrderCouponQuoteUseCase.isAuthFailure(rejected.status()) ->
                    log.error(OrderCouponQuoteUseCase.AUTH_FAILED_LOG + " 쿠폰 내부 API가 해제의 인증·권한을 거절했다. "
                            + "내부 API 키·설정을 확인한다. 작업은 다시 보낸다 orderId={}, workId={}, httpStatus={}", orderId,
                            work.getWorkId(), rejected.status());
            case CouponCallResult.Rejected<CouponReleaseResponse> rejected -> {
                work.markDead(now);
                log.error(OrderCouponReserveUseCase.WORK_DEAD_LOG + " 쿠폰이 해제를 거절해 자동 처리를 멈췄다. 운영자가 확인한다 "
                                + "orderId={}, workId={}, httpStatus={}, couponCode={}, memberCouponId={}", orderId,
                        work.getWorkId(), rejected.status(), rejected.code(), rejected.memberCouponId());
            }
            case CouponCallResult.NotSent<CouponReleaseResponse> notSent ->
                    log.warn("쿠폰 해제를 보내지 못해 다음 시도 시각에 다시 보낸다 orderId={}, workId={}, attemptCount={}, reason={}",
                            orderId, work.getWorkId(), work.getAttemptCount(), notSent.reason());
            case CouponCallResult.Unknown<CouponReleaseResponse> unknown ->
                    log.warn("쿠폰 해제 결과를 몰라 다음 시도 시각에 같은 내용으로 다시 보낸다 orderId={}, workId={}, attemptCount={}, "
                                    + "httpStatus={}, reason={}", orderId, work.getWorkId(), work.getAttemptCount(),
                            unknown.status(), unknown.reason());
        }
    }
}
