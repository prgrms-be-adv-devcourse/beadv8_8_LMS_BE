package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.CartRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.LectureSettlementCommand;
import com.hapbang.shared.billing.dto.LectureSettlementResultDto;
import com.hapbang.shared.billing.port.BillingPort;
import com.hapbang.shared.billing.port.LectureSettlementPort;

/**
 * 주문 (장바구니 주문·바로 구매, 설계 6.4). 주문 저장, 항목별 HP 보류, 항목별 판매자 지급 예정 등록(#182), 장바구니 정리를
 * 호출한 Facade의 한 트랜잭션에서 한다. 보류나 지급 예정 등록이 하나라도 실패하면(잔액 부족, 판매자 지갑 없음 등) billing 예외가
 * 그대로 전파되어 주문·보류·지급 예정·장바구니 변경이 모두 롤백된다.
 */
@Service
public class OrderCreateUseCase {

    /** 보유 중인 강의의 중복 주문을 막는 부분 유일 인덱스 (D5) */
    private static final String OWNED_LECTURE_INDEX = "uk_order_item_buyer_lecture_owned";

    private final LectureRepository lectureRepository;
    private final LectureOrderRepository lectureOrderRepository;
    private final CartRepository cartRepository;
    private final BillingPort billingPort;
    private final LectureSettlementPort lectureSettlementPort;
    private final Clock clock;

    public OrderCreateUseCase(LectureRepository lectureRepository, LectureOrderRepository lectureOrderRepository,
                              CartRepository cartRepository, BillingPort billingPort,
                              LectureSettlementPort lectureSettlementPort,
                              @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureRepository = lectureRepository;
        this.lectureOrderRepository = lectureOrderRepository;
        this.cartRepository = cartRepository;
        this.billingPort = billingPort;
        this.lectureSettlementPort = lectureSettlementPort;
        this.clock = clock;
    }

    public OrderResponse create(Long buyerUserId, List<Long> lectureIds) {
        LectureOrder order = LectureOrder.place(buyerUserId, findLectures(lectureIds),
                lectureOrderRepository.findHeldLectureIds(buyerUserId, lectureIds), LocalDateTime.now(clock));
        // 보류의 참조 ID(주문 항목 ID)를 얻고, 같은 강의의 동시 주문을 보류 전에 걸러낸다
        save(order);
        order.getItems().forEach(item -> hold(buyerUserId, item));

        LocalDateTime paidAt = LocalDateTime.now(clock);
        order.stampPaidAt(paidAt);
        // 보류한 항목마다 판매자 지급 예정 건을 등록한다 (#182). 실패하면 주문·보류까지 함께 롤백된다
        order.getItems().forEach(item -> scheduleSettlement(buyerUserId, item));
        cartRepository.findByUserId(buyerUserId)
                .ifPresent(cart -> lectureIds.forEach(lectureId -> cart.remove(lectureId, paidAt)));
        return OrderResponse.of(order);
    }

    /**
     * 요청 순서대로 강의를 읽는다. 같은 강의가 두 번 있으면 그대로 넘겨 도메인이 거부하게 한다(D31).
     * 없는 강의는 판매 중이 아닌 강의와 같게 본다.
     */
    private List<Lecture> findLectures(List<Long> lectureIds) {
        Map<Long, Lecture> lectures = lectureRepository.findAllById(lectureIds).stream()
                .collect(Collectors.toMap(Lecture::getId, Function.identity()));
        if (!lectures.keySet().containsAll(lectureIds)) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        return lectureIds.stream().map(lectures::get).toList();
    }

    /** 보유 검사를 동시에 통과한 같은 강의 주문은 부분 유일 인덱스에 걸린다. 이미 보유한 강의로 응답한다 (D45) */
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

    /** 항목마다 그 가격을 보류한다 (D36). 결과가 요청과 다르면 돈이 맞지 않으므로 주문 전체를 롤백한다 (D43) */
    private void hold(Long buyerUserId, OrderItem item) {
        HoldResultDto result = billingPort.hold(new HoldCommand(buyerUserId, BillingReferenceType.LECTURE_ORDER_ITEM,
                item.getId(), item.getPriceHp()));
        if (result.replayed() || result.heldHp() != item.getPriceHp()) {
            throw new IllegalStateException("주문 항목 보류 결과가 요청과 다릅니다. orderItemId=" + item.getId()
                    + ", priceHp=" + item.getPriceHp() + ", result=" + result);
        }
    }

    /**
     * 판매자 지급 예정 건을 항목 가격·구매 시각으로 등록한다 (#182). 새 항목이므로 재요청이면 안 되고, billing이 정한 지급 가능 시각은
     * 강의가 계산한 지급 예정 시각(구매일 + 31일 00:00, 설계 5장)과 같아야 한다. 다르면 지급 시점이 어긋나므로 주문 전체를 롤백한다 (D43)
     */
    private void scheduleSettlement(Long buyerUserId, OrderItem item) {
        LectureSettlementResultDto result = lectureSettlementPort.schedule(new LectureSettlementCommand(buyerUserId,
                item.getSellerUserId(), item.getId(), item.getPriceHp(), toInstant(item.getPurchasedAt())));
        if (result.replayed() || result.amountHp() != item.getPriceHp()
                || !result.payableAt().equals(toInstant(item.getPayoutScheduledAt()))) {
            throw new IllegalStateException("주문 항목 지급 예정 등록 결과가 요청과 다릅니다. orderItemId=" + item.getId()
                    + ", priceHp=" + item.getPriceHp() + ", payoutScheduledAt=" + item.getPayoutScheduledAt()
                    + ", result=" + result);
        }
    }

    /** 강의 시각은 {@link LectureConfig#ZONE} 기준 LocalDateTime이다 */
    private static Instant toInstant(LocalDateTime dateTime) {
        return dateTime.atZone(LectureConfig.ZONE).toInstant();
    }
}
