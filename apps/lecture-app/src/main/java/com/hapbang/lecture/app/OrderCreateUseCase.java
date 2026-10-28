package com.hapbang.lecture.app;

import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.*;
import com.hapbang.lecture.out.CartRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.port.BillingPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 (장바구니 주문·바로 구매, 설계 6.4). 주문 저장, 항목별 HP 보류, 장바구니 정리를 호출한 Facade의 한 트랜잭션에서 한다.
 * 보류가 하나라도 실패하면(잔액 부족 등) billing 예외가 그대로 전파되어 주문·보류·장바구니 변경이 모두 롤백된다.
 */
@Service
public class OrderCreateUseCase {

    /**
     * 보유 중인 강의의 중복 주문을 막는 부분 유일 인덱스 (D5)
     */
    private static final String OWNED_LECTURE_INDEX = "uk_order_item_buyer_lecture_owned";

    private final LectureRepository lectureRepository;
    private final LectureOrderRepository lectureOrderRepository;
    private final CartRepository cartRepository;
    private final BillingPort billingPort;
    private final Clock clock;

    public OrderCreateUseCase(
            LectureRepository lectureRepository,
            LectureOrderRepository lectureOrderRepository,
            CartRepository cartRepository,
            BillingPort billingPort,
            @Qualifier(LectureConfig.CLOCK) Clock clock
    ) {
        this.lectureRepository = lectureRepository;
        this.lectureOrderRepository = lectureOrderRepository;
        this.cartRepository = cartRepository;
        this.billingPort = billingPort;
        this.clock = clock;
    }

    public OrderResponse create(Long buyerUserId, List<Long> lectureIds) {
        // 항목을 강의 ID 오름차순으로 만들어 INSERT 순서를 같게 한다. 같은 구매자가 같은 강의들을 다른 순서로 동시에
        // 주문해도 늦은 쪽은 교착 없이 앞 주문을 기다렸다가 보유 강의 중복(D45)으로 끝난다 (D55)
        List<Long> sortedLectureIds = lectureIds.stream().sorted().toList();
        LectureOrder order = LectureOrder.place(
                buyerUserId, findLectures(sortedLectureIds),
                lectureOrderRepository.findHeldLectureIds(buyerUserId, sortedLectureIds),
                LocalDateTime.now(clock)
        );

        // 보류의 참조 ID(주문 항목 ID)를 얻고, 같은 강의의 동시 주문을 보류 전에 걸러낸다
        save(order);
        order.getItems().forEach(item -> hold(buyerUserId, item));

        LocalDateTime paidAt = LocalDateTime.now(clock);
        order.stampPaidAt(paidAt);
        cartRepository.findByUserId(buyerUserId)
                .ifPresent(cart -> lectureIds.forEach(lectureId -> cart.remove(lectureId, paidAt)));
        return OrderResponse.of(order);
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

    /**
     * 항목마다 그 가격을 보류한다 (D36). 결과가 요청과 다르면 돈이 맞지 않으므로 주문 전체를 롤백한다 (D43)
     */
    private void hold(Long buyerUserId, OrderItem item) {
        HoldResultDto result = billingPort.hold(new HoldCommand(buyerUserId, BillingReferenceType.LECTURE_ORDER_ITEM,
                item.getId(), item.getPriceHp()));
        if (result.replayed() || result.heldHp() != item.getPriceHp()) {
            throw new IllegalStateException("주문 항목 보류 결과가 요청과 다릅니다. orderItemId=" + item.getId()
                    + ", priceHp=" + item.getPriceHp() + ", result=" + result);
        }
    }
}
