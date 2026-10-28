package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.port.BillingPort;

import lombok.extern.slf4j.Slf4j;

/**
 * 지급 (#150, D50). 지급 예정 시각(구매일 다음 날부터 30일 경과)이 지나고 이의 제기가 없거나 기각된 {@code CONFIRMED} 항목의
 * 보류 HP를 판매자 수익 HP로 옮기고 {@code PAID_OUT}으로 바꾼다. 지급은 billing이 차감했던 HP 종류 그대로 한다 (D42).
 * <ul>
 *     <li>항목마다 새 트랜잭션을 열고, 그 안에서 항목 상태 변경과 {@code BillingPort.payout}을 함께 한다 (D3).
 *     billing이 실패하면 항목 상태도 롤백된다.</li>
 *     <li>예외는 트랜잭션 밖에서 항목별로 잡아 다음 항목으로 넘어간다. 실패한 항목은 {@code CONFIRMED}로 남는다 (설계 6.4).</li>
 *     <li>실패는 {@link PayoutFailure}로 분류해 새 트랜잭션에서 항목에 기록한다(D54). 일시 실패는 다음 실행에서 다시 처리하고,
 *     영구 실패는 격리해 관리자가 해제할 때까지 처리하지 않는다. 어느 쪽도 지급 완료({@code PAID_OUT})로 보지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderPayoutUseCase {

    /** 지급 요청 ID. 항목은 한 번만 지급되므로 항목 ID로 정해진다 (D43, D50) */
    private static final String PAYOUT_REQUEST_ID_PREFIX = "LECTURE-PAYOUT-";
    /** 일시 실패가 이 횟수 이상 반복되면 ERROR로 남긴다 (D54) */
    private static final int REPEATED_FAILURE_COUNT = 3;

    private final LectureOrderRepository lectureOrderRepository;
    private final LectureOrderSupport lectureOrderSupport;
    private final BillingPort billingPort;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderPayoutUseCase(LectureOrderRepository lectureOrderRepository,
                              LectureOrderSupport lectureOrderSupport,
                              BillingPort billingPort,
                              PlatformTransactionManager transactionManager,
                              @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderRepository = lectureOrderRepository;
        this.lectureOrderSupport = lectureOrderSupport;
        this.billingPort = billingPort;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void payOutDueItems() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> itemIds = lectureOrderRepository.findPayoutDueItemIds(now);
        int failed = 0;
        int quarantined = 0;
        for (Long itemId : itemIds) {
            try {
                transaction.executeWithoutResult(status -> payOut(itemId, now));
            } catch (RuntimeException exception) {
                failed++;
                if (handleFailure(itemId, exception)) {
                    quarantined++;
                }
            }
        }
        if (!itemIds.isEmpty()) {
            log.info("지급 대상 {}건, 실패 {}건, 격리 {}건", itemIds.size(), failed, quarantined);
        }
    }

    private void payOut(Long itemId, LocalDateTime now) {
        // 조회한 뒤 이의 제기가 먼저 접수되는 등 대상이 아니게 되었으면 그대로 둔다
        lectureOrderSupport.findOrderByItemId(itemId).payOutItem(itemId, now).ifPresent(item -> {
            // 같은 항목을 동시에 바꾼 요청(낙관적 락 충돌)을 billing 호출 전에 확인한다 (설계 6.2)
            lectureOrderRepository.flush();
            requestPayout(item);
        });
    }

    private void requestPayout(OrderItem item) {
        PayoutResultDto result = billingPort.payout(PayoutCommand.full(item.getBuyerUserId(),
                BillingReferenceType.LECTURE_ORDER_ITEM, item.getId(), PAYOUT_REQUEST_ID_PREFIX + item.getId(),
                item.getSellerUserId(), SettlementSourceType.LECTURE_ORDER_ITEM, item.getId()));
        // 항목 보류는 항목 가격 그대로 걸었으므로 전부 지급하면 가격과 같아야 한다 (D43)
        if (result.paidHp() != item.getPriceHp()) {
            throw new PayoutAmountMismatchException("지급액이 항목 가격과 다릅니다. orderItemId=" + item.getId()
                    + ", priceHp=" + item.getPriceHp() + ", result=" + result);
        }
    }

    /**
     * 롤백된 지급의 실패를 분류해 새 트랜잭션에서 기록하고 로그를 남긴다. 격리했으면 {@code true}다 (D54).
     * 기록이 실패해도(같은 항목을 동시에 바꾼 경우 등) 항목은 격리되지 않은 채 남아 다음 실행에서 다시 처리된다.
     */
    private boolean handleFailure(Long itemId, RuntimeException exception) {
        PayoutFailure failure = PayoutFailure.of(exception);
        Optional<OrderItem> recorded;
        try {
            recorded = transaction.execute(status -> recordFailure(itemId, failure, LocalDateTime.now(clock)));
        } catch (RuntimeException recordException) {
            exception.addSuppressed(recordException);
            log.error("지급 실패를 기록하지 못했다, 다음 실행에서 다시 처리한다 orderItemId={}, code={}", itemId,
                    failure.code(), exception);
            return false;
        }
        if (recorded == null || recorded.isEmpty()) {
            log.warn("지급 실패, 이미 지급 대상이 아니라 기록하지 않는다 orderItemId={}, code={}", itemId, failure.code(),
                    exception);
            return false;
        }
        int failures = recorded.get().getPayoutFailureCount();
        if (failure.permanent()) {
            log.error("지급 실패, 다시 시도해도 풀리지 않아 격리한다. 원인을 고친 뒤 관리자가 격리를 해제한다 "
                    + "orderItemId={}, code={}, failures={}", itemId, failure.code(), failures, exception);
            return true;
        }
        if (failures >= REPEATED_FAILURE_COUNT) {
            log.error("지급 실패가 반복된다, 다음 실행에서 다시 처리한다 orderItemId={}, code={}, failures={}", itemId,
                    failure.code(), failures, exception);
        } else {
            log.warn("지급 실패, 다음 실행에서 다시 처리한다 orderItemId={}, code={}, failures={}", itemId, failure.code(),
                    failures, exception);
        }
        return false;
    }

    /** 지급 조회({@link LectureOrderSupport})와 다른 경로로 읽어, 조회 단계의 실패도 기록한다 */
    private Optional<OrderItem> recordFailure(Long itemId, PayoutFailure failure, LocalDateTime failedAt) {
        return lectureOrderRepository.findByItemId(itemId)
                .flatMap(order -> order.recordPayoutFailure(itemId, failure.code(), failure.permanent(), failedAt));
    }
}
