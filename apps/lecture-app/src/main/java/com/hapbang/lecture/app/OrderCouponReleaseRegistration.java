package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderCouponWork;
import com.hapbang.lecture.domain.OrderCouponWorkKind;
import com.hapbang.lecture.domain.OrderCouponWorkStatus;
import com.hapbang.lecture.out.OrderCouponWorkRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 실패가 확정되는 트랜잭션에서 예약한 쿠폰의 해제 작업({@code lecture-order:<주문 ID>:release})을 만든다.
 * 결제 실패는 보상 반환이 모두 끝난 뒤에만 확정되므로 해제도 그 뒤다.
 * <ul>
 *     <li>예약 작업이 {@code RESERVED}일 때만 만든다. 쿠폰이 예약을 확인해 준 경우이고, 처음에 결과를 몰라 다시 보낸 뒤 예약된
 *     경우도 같다. 예약 generation이 남아 있어 해제 요청을 만들 수 있다.</li>
 *     <li>거절({@code REJECTED})은 쿠폰이 하나도 예약하지 않았으므로 만들지 않는다. 쿠폰 없는 주문도 만들지 않는다.</li>
 *     <li>결과를 모르는 예약({@code PENDING}·{@code DEAD})은 결제 실패까지 가지 않는다(보류는 예약 뒤에만 등록한다).</li>
 *     <li>이미 해제 작업이 있으면 그대로 둔다.</li>
 * </ul>
 * 보내기는 주문 요청(트랜잭션 밖)이나 쿠폰 작업 재전송 배치가 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCouponReleaseRegistration {

    private final OrderCouponWorkRepository orderCouponWorkRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OrderCouponWork> registerIfReserved(LectureOrder order, LocalDateTime now) {
        boolean reserved = orderCouponWorkRepository.findByOrderIdAndKind(order.getId(), OrderCouponWorkKind.RESERVE)
                .map(work -> work.getStatus() == OrderCouponWorkStatus.RESERVED)
                .orElse(false);
        if (!reserved) {
            return Optional.empty();
        }
        Optional<OrderCouponWork> existing = orderCouponWorkRepository.findByOrderIdAndKind(order.getId(),
                OrderCouponWorkKind.RELEASE);
        if (existing.isPresent()) {
            return existing;
        }
        OrderCouponWork release = orderCouponWorkRepository.save(OrderCouponWork.release(order.getId(), now));
        log.info("결제 실패한 주문의 쿠폰 해제 작업을 만들었다 orderId={}, workId={}", order.getId(), release.getWorkId());
        return Optional.of(release);
    }
}
