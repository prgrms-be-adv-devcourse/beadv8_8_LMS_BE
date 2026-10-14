package com.hapbang.lecture.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.app.dto.OrderSummaryResponse;
import com.hapbang.lecture.app.dto.PurchasedLectureResponse;

import lombok.RequiredArgsConstructor;

/**
 * 강의 주문 진입점 (설계 8.5). 유저·인플루언서·인증된 인플루언서만 쓴다. 관리자는 불가 (설계 9장, D16).
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")
public class LectureOrderFacade {

    private final LectureOrderSupport lectureOrderSupport;
    private final OrderConfirmUseCase orderConfirmUseCase;
    private final OrderPlayConsentUseCase orderPlayConsentUseCase;
    private final PurchasedLectureSupport purchasedLectureSupport;

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
}
