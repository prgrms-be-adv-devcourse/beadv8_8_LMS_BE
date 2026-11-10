package com.hapbang.lecture.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.OrderCouponWorkDetailResponse;
import com.hapbang.lecture.app.dto.OrderCouponWorkFailResponse;
import com.hapbang.lecture.app.dto.OrderCouponWorkResponse;
import com.hapbang.lecture.domain.OrderCouponWorkKind;
import com.hapbang.lecture.domain.OrderCouponWorkStatus;
import com.hapbang.lecture.out.OrderCouponWorkRepository;

import lombok.RequiredArgsConstructor;

/**
 * 관리자의 주문 쿠폰 작업(예약·해제) 조회·다시 시도·결제 실패 확정·해제 확인 완료 진입점. 관리자 여부는 여기서 검사한다.
 * /admin 경로는 SecurityConfig도 함께 막는다. 조치는 쿠폰 예약 상태 조회를 트랜잭션 밖에서 하므로 트랜잭션을 열지 않고
 * {@link OrderCouponWorkAdminUseCase}가 짧은 트랜잭션으로 나눠 기록한다.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class LectureCouponWorkAdminFacade {

    private final OrderCouponWorkRepository orderCouponWorkRepository;
    private final OrderCouponWorkAdminUseCase orderCouponWorkAdminUseCase;

    /** 작업 목록 (최신순, 정렬은 쿼리에 고정해 요청의 정렬은 무시한다) */
    @Transactional(readOnly = true)
    public Page<OrderCouponWorkResponse> getCouponWorks(OrderCouponWorkStatus status, OrderCouponWorkKind kind,
                                                        Pageable pageable) {
        PageRequest unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return orderCouponWorkRepository.search(status, kind, unsorted)
                .map(row -> OrderCouponWorkResponse.of(row.work(), row.orderStatus()));
    }

    /** 작업 상세: 주문 항목별 쿠폰 계산 근거와 쿠폰 쪽 예약 상태(트랜잭션 밖에서 조회, 못 부르면 UNAVAILABLE) */
    public OrderCouponWorkDetailResponse getCouponWork(String workId) {
        OrderCouponWorkAdminUseCase.WorkDetail detail = orderCouponWorkAdminUseCase.detail(workId);
        OrderCouponWorkAdminUseCase.CouponReservationView reservation =
                orderCouponWorkAdminUseCase.reservationStatus(workId);
        return new OrderCouponWorkDetailResponse(OrderCouponWorkResponse.of(detail.work(), detail.orderStatus()),
                detail.coupons().stream().map(OrderCouponWorkDetailResponse.ItemCoupon::of).toList(),
                reservation.couponStatus(), reservation.reservation());
    }

    /** {@code DEAD} 작업 다시 시도. 재전송 배치가 같은 작업 ID로 다시 보낸다 */
    public void retry(Long adminUserId, String workId) {
        orderCouponWorkAdminUseCase.retry(adminUserId, workId);
    }

    /** {@code DEAD} 예약 작업의 결제 실패 확정 */
    public OrderCouponWorkFailResponse fail(Long adminUserId, String workId) {
        OrderCouponWorkAdminUseCase.FailOutcome outcome = orderCouponWorkAdminUseCase.fail(adminUserId, workId);
        OrderCouponWorkAdminUseCase.WorkDetail detail = orderCouponWorkAdminUseCase.detail(workId);
        return new OrderCouponWorkFailResponse(outcome.name(),
                OrderCouponWorkResponse.of(detail.work(), detail.orderStatus()));
    }

    /** {@code DEAD} 해제 작업의 해제 확인 완료 */
    public void resolve(Long adminUserId, String workId) {
        orderCouponWorkAdminUseCase.resolve(adminUserId, workId);
    }
}
