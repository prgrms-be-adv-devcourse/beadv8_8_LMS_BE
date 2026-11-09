package com.hapbang.shared.coupon.api;

import java.util.List;

/**
 * 쿠폰 해제 내부 API({@code POST /api/v1/internal/coupons/releases})의 응답. 요청의 모든 사용 시도가 해제되었을 때만 온다.
 *
 * @param workId 해제 작업 ID
 * @param usages 해제한 사용 시도 목록
 */
public record CouponReleaseResponse(
        String workId,
        List<Usage> usages
) {

    public CouponReleaseResponse {
        usages = List.copyOf(usages);
    }

    /**
     * 해제한 사용 시도 하나.
     *
     * @param memberCouponId 발급 쿠폰 ID
     * @param generation     해제한 사용 시도의 generation
     * @param releasedTo     해제 뒤 발급 쿠폰 상태: {@code AVAILABLE}(만료 전), {@code EXPIRED}(만료 뒤)
     */
    public record Usage(
            long memberCouponId,
            long generation,
            String releasedTo
    ) {
    }
}
