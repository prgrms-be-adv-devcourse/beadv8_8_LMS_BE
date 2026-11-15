package com.hapbang.coupon.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.coupon.app.dto.CouponUsageView;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.out.CouponUsageRepository;

import lombok.RequiredArgsConstructor;

/**
 * 회원의 쿠폰 사용 이력 조회. 로그인 회원의 사용 시도만 조건으로 거른다(다른 회원 것은 결과에 없을 뿐 404·403이 아니다).
 * 순서는 예약 최신순(같은 시각이면 ID 역순)으로 고정하고 요청의 정렬은 쓰지 않는다. 페이지 크기는 {@link CouponSupport#MAX_LIST_SIZE}로
 * 자른다(운영은 강의 앱 페이지 설정도 같은 값으로 자른다). 같은 주문 항목에 해제 뒤 다시 예약하면 두 행이고 숨기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CouponUsageHistoryQuery {

    private final CouponUsageRepository couponUsageRepository;

    /** @param status 없으면 전체 */
    @Transactional(readOnly = true)
    public Page<CouponUsageView> myUsages(long memberId, CouponUsageStatus status, Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), CouponSupport.MAX_LIST_SIZE));
        return couponUsageRepository.findMyUsages(memberId, status, page)
                .map(used -> CouponUsageView.of(used.usage(), used.coupon()));
    }
}
