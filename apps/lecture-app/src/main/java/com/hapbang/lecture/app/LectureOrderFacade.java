package com.hapbang.lecture.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.OrderCouponSelection;
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
    private final OrderCouponQuoteUseCase orderCouponQuoteUseCase;
    private final OrderCouponReserveUseCase orderCouponReserveUseCase;
    private final OrderCouponReleaseUseCase orderCouponReleaseUseCase;
    private final OrderConfirmUseCase orderConfirmUseCase;
    private final OrderCancelUseCase orderCancelUseCase;
    private final OrderPlayConsentUseCase orderPlayConsentUseCase;
    private final PurchasedLectureSupport purchasedLectureSupport;

    /**
     * 주문 (장바구니 주문·바로 구매, D58). 트랜잭션을 나눠 주문을 저장한 뒤 트랜잭션 밖에서 항목마다 HP를 보류한다.
     * 결제가 끝나면 {@code PAID}, 아직 결제 단계면 처리 중 주문을 돌려주고, 결제 실패면 {@code INSUFFICIENT_BALANCE}(탈퇴로 닫힌 지갑이 거절했으면 {@code WALLET_CLOSED}, D76)다.
     */
    public OrderResponse createOrder(Long userId, List<Long> lectureIds) {
        return createOrder(userId, lectureIds, List.of(), null);
    }

    /**
     * 쿠폰을 고를 수 있는 주문. 쿠폰을 고르지 않았으면 쿠폰 모듈을 부르지 않고 위 주문과 같다.
     * 쿠폰을 골랐으면 트랜잭션 밖에서 쿠폰 할인을 다시 계산해 구매자가 본 금액({@code expectedBuyerAmountHp})과 맞춰 본 뒤,
     * 주문·할인 스냅샷·쿠폰 예약 작업을 저장하고, 쿠폰을 예약한 뒤에야 보류 작업을 등록해 보류를 시도한다.
     * 예약이 거절되면 {@code COUPON_RESERVATION_REJECTED}(409)이고, 예약 결과를 모르면 결제 처리 중 주문을 돌려준다.
     * 예약 뒤 결제 실패로 끝나면 예약한 쿠폰의 해제를 한 번 보낸다.
     */
    public OrderResponse createOrder(Long userId, List<Long> lectureIds, List<OrderCouponSelection> coupons,
                                     Long expectedBuyerAmountHp) {
        if (coupons == null || coupons.isEmpty()) {
            OrderCreateUseCase.Placement placement = orderCreateUseCase.place(userId, lectureIds);
            orderCreateUseCase.tryHolds(placement);
            return orderCreateUseCase.result(userId, placement.orderId());
        }
        OrderCouponQuote quote = orderCouponQuoteUseCase.quote(userId, lectureIds, coupons, expectedBuyerAmountHp);
        OrderCreateUseCase.Placement placement = orderCreateUseCase.placeWithCoupons(userId, lectureIds, quote);
        List<Long> holdTaskIds = orderCouponReserveUseCase.reserve(placement.orderId());
        orderCreateUseCase.tryHolds(new OrderCreateUseCase.Placement(placement.orderId(), holdTaskIds));
        // 결제 실패로 끝났으면 예약한 쿠폰을 바로 한 번 해제해 본다. 결과를 모르면 쿠폰 작업 재전송 배치가 잇는다
        orderCouponReleaseUseCase.release(placement.orderId());
        return orderCreateUseCase.result(userId, placement.orderId());
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

    /**
     * 구매 취소 (D59 (1)). 트랜잭션을 나눠 구매 취소 처리 중과 반환 작업을 저장한 뒤 트랜잭션 밖에서 반환을 한 번 시도한다.
     * 반환이 끝나면 {@code CANCELED}, 아니면 {@code CANCEL_PENDING} 항목을 돌려준다
     */
    public OrderItemResponse cancel(Long userId, Long orderId, Long orderItemId) {
        Long releaseTaskId = orderCancelUseCase.request(userId, orderId, orderItemId);
        orderCancelUseCase.tryRelease(releaseTaskId);
        return orderCancelUseCase.result(userId, orderItemId);
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

    /** 판매 내역. 강의를 판매하는 인증된 인플루언서만 본다 (메서드 권한이 클래스 권한보다 우선한다, D51) */
    @PreAuthorize("hasRole('VERIFIED_INFLUENCER')")
    @Transactional(readOnly = true)
    public Page<SaleResponse> getSales(Long userId, Pageable pageable) {
        return lectureOrderSupport.getSales(userId, pageable);
    }
}
