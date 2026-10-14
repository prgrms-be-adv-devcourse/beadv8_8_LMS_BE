package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.port.BillingPort;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException.Reason;

/**
 * 이의 제기 판단 (설계 6.4). 인정하면 항목 반환과 그 항목의 보류 HP 반환을 호출한 쪽(고객센터) 트랜잭션에서 함께 한다 (D3·D9).
 * 반환은 billing이 차감했던 HP 종류 그대로 한다 (D42).
 */
@Service
public class OrderObjectionResolveUseCase {

    /** 반환 요청 ID. 항목은 한 번만 반환되므로 항목 ID로 정해진다 (D43) */
    private static final String RELEASE_REQUEST_ID_PREFIX = "LECTURE-RETURN-";

    private final LectureOrderSupport lectureOrderSupport;
    private final LectureOrderRepository lectureOrderRepository;
    private final BillingPort billingPort;
    private final Clock clock;

    public OrderObjectionResolveUseCase(LectureOrderSupport lectureOrderSupport,
                                        LectureOrderRepository lectureOrderRepository, BillingPort billingPort,
                                        @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderSupport = lectureOrderSupport;
        this.lectureOrderRepository = lectureOrderRepository;
        this.billingPort = billingPort;
        this.clock = clock;
    }

    public void resolveObjection(Long orderItemId, Long inquiryId, boolean accepted, Long adminUserId) {
        // 인정은 주문을 닫을 수 있으므로 구매 취소처럼 주문을 잠근다 (D46). 없는 항목은 판단할 이의 제기가 없는 것이다
        LectureOrder order = lectureOrderSupport.findOrderForUpdateByItemId(orderItemId)
                .orElseThrow(() -> new LectureObjectionRefusedException(Reason.NOT_FILED));
        OrderItem item = order.resolveObjection(orderItemId, inquiryId, accepted, adminUserId,
                LocalDateTime.now(clock));
        // 같은 항목을 동시에 바꾼 변경(낙관적 락 충돌)을 billing 호출 전에 확인한다 (설계 6.2)
        lectureOrderRepository.flush();
        if (!accepted) {
            return;
        }

        ReleaseResultDto result = billingPort.release(ReleaseCommand.full(item.getBuyerUserId(),
                BillingReferenceType.LECTURE_ORDER_ITEM, item.getId(), RELEASE_REQUEST_ID_PREFIX + item.getId()));
        // 항목 보류는 항목 가격 그대로 걸었으므로 전부 반환하면 가격과 같아야 한다 (D43)
        if (result.releasedHp() != item.getPriceHp()) {
            throw new IllegalStateException("이의 제기 인정 반환액이 항목 가격과 다릅니다. orderItemId=" + item.getId()
                    + ", priceHp=" + item.getPriceHp() + ", result=" + result);
        }
    }
}
