package com.hapbang.lecture.app;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.app.dto.OrderSummaryResponse;
import com.hapbang.lecture.app.dto.SaleResponse;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureOrderRepository;

import lombok.RequiredArgsConstructor;

/** 주문 조회. 응답 DTO 변환은 호출한 Facade의 트랜잭션 안에서 끝난다. */
@Service
@RequiredArgsConstructor
public class LectureOrderSupport {

    private static final Sort LATEST = Sort.by(Sort.Order.desc("orderedAt"), Sort.Order.desc("id"));

    private final LectureOrderRepository lectureOrderRepository;

    /** 구매자 본인의 주문. 남의 주문과 없는 주문은 모두 {@code ORDER_NOT_FOUND} (D32) */
    public LectureOrder findOrder(Long buyerUserId, Long orderId) {
        return lectureOrderRepository.findByIdAndBuyerUserId(orderId, buyerUserId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.ORDER_NOT_FOUND));
    }

    /** 구매자 본인의 주문을 잠가서 읽는다. 주문을 닫을 수 있는 변경에 쓴다 (D46) */
    public LectureOrder findOrderForUpdate(Long buyerUserId, Long orderId) {
        return lectureOrderRepository.findForUpdate(orderId, buyerUserId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.ORDER_NOT_FOUND));
    }

    /**
     * 이 주문 항목이 속한 주문을 잠가서 읽는다(구매자 확인 없음). 구매자를 모르는 쪽(이의 제기 판단)이 주문을 닫을 수 있을 때 쓴다 (D46).
     * 주문 ID만 먼저 읽어, 잠그기 전에 항목을 영속성 컨텍스트에 올리지 않는다.
     */
    public Optional<LectureOrder> findOrderForUpdateByItemId(Long orderItemId) {
        return lectureOrderRepository.findOrderIdByItemId(orderItemId)
                .flatMap(lectureOrderRepository::findForUpdateById);
    }

    /** 이 주문 항목이 속한 주문 (배치용, 구매자 확인 없음) */
    public LectureOrder findOrderByItemId(Long orderItemId) {
        return lectureOrderRepository.findByItemId(orderItemId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    /** 구매자 본인의 주문 항목. 남의 항목과 없는 항목은 모두 {@code ORDER_ITEM_NOT_FOUND} (D32) */
    public OrderItem findItem(Long buyerUserId, Long orderItemId) {
        return lectureOrderRepository.findItemOfBuyer(orderItemId, buyerUserId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    /** 구매자가 이 강의를 보유한(구매·구매 확정·지급) 항목 */
    public Optional<OrderItem> findHeldItem(Long buyerUserId, Long lectureId) {
        return lectureOrderRepository.findHeldItem(buyerUserId, lectureId);
    }

    /** 최근 주문부터. 정렬은 고정이고 요청의 정렬은 무시한다. */
    public Page<OrderSummaryResponse> getOrders(Long buyerUserId, Pageable pageable) {
        PageRequest sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);
        return lectureOrderRepository.findByBuyerUserId(buyerUserId, sorted).map(OrderSummaryResponse::of);
    }

    public OrderResponse getOrder(Long buyerUserId, Long orderId) {
        return OrderResponse.of(findOrder(buyerUserId, orderId));
    }

    /** 판매자의 판매 내역. 최근 구매순이고 정렬은 쿼리에 고정되어 요청의 정렬은 무시한다 (D51) */
    public Page<SaleResponse> getSales(Long sellerUserId, Pageable pageable) {
        PageRequest unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return lectureOrderRepository.findSales(sellerUserId, unsorted).map(SaleResponse::of);
    }
}
