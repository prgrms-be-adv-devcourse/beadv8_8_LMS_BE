package com.hapbang.coupon.app.port;

import java.util.List;

/** 대사용 발급 요청 결과 조회 */
public interface IssueResultReader {

    /** 쿠폰의 발급 요청 결과 전부. 행 수는 쿠폰 수량(쿠폰별 상한) 안이다 */
    List<IssueResultRow> findRowsByCouponId(long couponId);
}
