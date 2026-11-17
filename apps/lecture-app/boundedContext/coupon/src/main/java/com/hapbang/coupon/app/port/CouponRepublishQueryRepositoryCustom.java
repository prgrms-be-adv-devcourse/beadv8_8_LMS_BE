package com.hapbang.coupon.app.port;

import java.util.Collection;
import java.util.List;

import com.hapbang.coupon.domain.CouponStatus;

/**
 * 재발행 배치의 조회. 쓰기와 단건 조회는 쿠폰·발급 요청 저장소가 맡는다
 * <p>
 * {@link CouponRepublishQueryRepository}의 QueryDSL 조회 조각이다. 구현은 {@code out}의 {@code CouponRepublishQueryRepositoryCustomImpl}이다.
 */
public interface CouponRepublishQueryRepositoryCustom {

    /** Redis 발급 준비를 마친 쿠폰 중 상태가 {@code statuses}인 것. 쿠폰 ID 순서다 */
    List<RepublishTargetCoupon> findTargets(Collection<CouponStatus> statuses);

    /** {@code issueRequestIds} 중 DB에 결과가 있는 요청 ID */
    List<String> findRecordedIds(long couponId, Collection<String> issueRequestIds);
}
