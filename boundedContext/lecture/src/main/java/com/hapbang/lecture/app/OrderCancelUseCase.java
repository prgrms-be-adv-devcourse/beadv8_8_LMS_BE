package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.port.BillingPort;
import com.hapbang.shared.billing.port.LectureSettlementPort;

/**
 * 구매 취소 (설계 6.4). 항목 상태 변경, 그 항목의 보류 HP 반환, 판매자 지급 예정 건 취소(#182)를 호출한 Facade의 한 트랜잭션에서 한다 (D3).
 * 반환은 billing이 차감했던 HP 종류 그대로 한다 (D42). 반환이나 지급 예정 취소가 실패하면 항목 상태 변경까지 모두 롤백된다.
 */
@Service
public class OrderCancelUseCase {

    /** 반환 요청 ID. 항목은 한 번만 구매 취소되므로 항목 ID로 정해진다 (D43) */
    private static final String RELEASE_REQUEST_ID_PREFIX = "LECTURE-CANCEL-";

    private final LectureOrderSupport lectureOrderSupport;
    private final LectureOrderRepository lectureOrderRepository;
    private final BillingPort billingPort;
    private final LectureSettlementPort lectureSettlementPort;
    private final Clock clock;

    public OrderCancelUseCase(LectureOrderSupport lectureOrderSupport, LectureOrderRepository lectureOrderRepository,
                              BillingPort billingPort, LectureSettlementPort lectureSettlementPort,
                              @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderSupport = lectureOrderSupport;
        this.lectureOrderRepository = lectureOrderRepository;
        this.billingPort = billingPort;
        this.lectureSettlementPort = lectureSettlementPort;
        this.clock = clock;
    }

    public OrderItemResponse cancel(Long buyerUserId, Long orderId, Long orderItemId) {
        // 같은 주문의 다른 항목 구매 취소와 동시에 와도 마지막 항목 판정(주문 CANCELED)이 빠지지 않도록 주문을 잠근다 (D46)
        LectureOrder order = lectureOrderSupport.findOrderForUpdate(buyerUserId, orderId);
        OrderItem item = order.cancelItem(orderItemId, LocalDateTime.now(clock));
        // 같은 항목을 동시에 바꾼 요청(낙관적 락 충돌)을 billing 호출 전에 확인한다 (설계 6.2)
        lectureOrderRepository.flush();

        ReleaseResultDto result = billingPort.release(ReleaseCommand.full(buyerUserId,
                BillingReferenceType.LECTURE_ORDER_ITEM, item.getId(), RELEASE_REQUEST_ID_PREFIX + item.getId()));
        // 항목 보류는 항목 가격 그대로 걸었으므로 전부 반환하면 가격과 같아야 한다 (D43)
        if (result.releasedHp() != item.getPriceHp()) {
            throw new IllegalStateException("구매 취소 반환액이 항목 가격과 다릅니다. orderItemId=" + item.getId()
                    + ", priceHp=" + item.getPriceHp() + ", result=" + result);
        }
        // 반환 다음에 판매자 지급 예정 건을 취소한다 (#182). billing 락 순서(지갑 → 보류 → 지급 건)를 지키려고 반환 → 취소 순서로 부른다
        lectureSettlementPort.cancel(item.getId());
        return OrderItemResponse.of(item);
    }
}
