package com.hapbang.lecture.app;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.app.dto.OrderSummaryResponse;
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

    /** 이 주문 항목이 속한 주문 (배치용, 구매자 확인 없음) */
    public LectureOrder findOrderByItemId(Long orderItemId) {
        return lectureOrderRepository.findByItemId(orderItemId)
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
}
