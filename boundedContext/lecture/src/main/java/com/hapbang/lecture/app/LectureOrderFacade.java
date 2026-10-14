package com.hapbang.lecture.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.app.dto.OrderSummaryResponse;
import com.hapbang.lecture.app.dto.PurchasedLectureResponse;
import com.hapbang.lecture.app.dto.SaleResponse;

import lombok.RequiredArgsConstructor;

/**
 * 강의 주문 진입점 (설계 8.5). 유저·인플루언서·인증된 인플루언서만 쓴다. 관리자는 불가 (설계 9장, D16).
 * 판매 내역은 인증된 인플루언서만 쓴다.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")
public class LectureOrderFacade {

    private final LectureOrderSupport lectureOrderSupport;
    private final OrderCreateUseCase orderCreateUseCase;
    private final OrderConfirmUseCase orderConfirmUseCase;
    private final OrderCancelUseCase orderCancelUseCase;
    private final OrderPlayConsentUseCase orderPlayConsentUseCase;
    private final PurchasedLectureSupport purchasedLectureSupport;

    /** 주문 (장바구니 주문·바로 구매). 항목마다 HP를 보류한다 */
    @Transactional
    public OrderResponse createOrder(Long userId, List<Long> lectureIds) {
        return orderCreateUseCase.create(userId, lectureIds);
    }

    /** 내 주문 목록 */
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> getOrders(Long userId, Pageable pageable) {
        return lectureOrderSupport.getOrders(userId, pageable);
    }

    /** 주문 상세 */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long userId, Long orderId) {
        return lectureOrderSupport.getOrder(userId, orderId);
    }

    /** 직접 구매 확정 */
    @Transactional
    public OrderItemResponse confirm(Long userId, Long orderId, Long orderItemId) {
        return orderConfirmUseCase.confirm(userId, orderId, orderItemId);
    }

    /** 구매 취소. 그 항목의 보류 HP를 반환한다 */
    @Transactional
    public OrderItemResponse cancel(Long userId, Long orderId, Long orderItemId) {
        return orderCancelUseCase.cancel(userId, orderId, orderItemId);
    }

    /** 재생 동의 (첫 재생 구매 확정) */
    @Transactional
    public OrderItemResponse consentToPlay(Long userId, Long orderId, Long orderItemId) {
        return orderPlayConsentUseCase.consentToPlay(userId, orderId, orderItemId);
    }

    /** 시청 가능한 강의 */
    @Transactional(readOnly = true)
    public Page<PurchasedLectureResponse> getPurchasedLectures(Long userId, Pageable pageable) {
        return purchasedLectureSupport.getPurchasedLectures(userId, pageable);
    }

    /** 판매 내역. 강의를 판매하는 인증된 인플루언서만 본다 (메서드 권한이 클래스 권한보다 우선한다, D50) */
    @PreAuthorize("hasRole('VERIFIED_INFLUENCER')")
    @Transactional(readOnly = true)
    public Page<SaleResponse> getSales(Long userId, Pageable pageable) {
        return lectureOrderSupport.getSales(userId, pageable);
    }
}
