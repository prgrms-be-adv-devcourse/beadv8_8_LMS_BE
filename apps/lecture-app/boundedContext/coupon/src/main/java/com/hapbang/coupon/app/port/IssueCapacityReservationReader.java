package com.hapbang.coupon.app.port;

import java.util.List;

/** 쿠폰별 발급 저장소 용량 예약 조회 */
public interface IssueCapacityReservationReader {

    /** 예약이 있는 쿠폰 ID(대사 대상). 오래된 예약 순 */
    List<Long> findCouponIds();
}
