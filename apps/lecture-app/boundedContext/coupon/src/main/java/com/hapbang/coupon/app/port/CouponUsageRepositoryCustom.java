package com.hapbang.coupon.app.port;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.coupon.domain.CouponUsageStatus;

/**
 * 쿠폰 사용 시도 조회
 * <p>
 * {@link CouponUsageRepository}의 QueryDSL 조회 조각이다. 구현은 {@code out}의 {@code CouponUsageRepositoryCustomImpl}이다.
 */
public interface CouponUsageRepositoryCustom {

    /**
     * 회원의 쿠폰 사용 이력. 상태를 주면 그 상태만. 예약 최신순(같은 시각이면 ID 역순)으로 고정하고 {@code pageable}의 정렬은 쓰지 않는다
     * (정렬 없는 {@code Pageable}을 넘긴다). 쿠폰 정책과의 조인은 같은 쿠폰 스키마 안에서만 한다
     * ({@code idx_coupon_usage_member_reserved_at}).
     */
    Page<UsedCoupon> findMyUsages(long memberId, CouponUsageStatus status, Pageable pageable);
}
