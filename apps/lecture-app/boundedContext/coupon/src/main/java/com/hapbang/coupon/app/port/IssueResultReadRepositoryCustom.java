package com.hapbang.coupon.app.port;

import java.util.List;

/**
 * 대사용 발급 요청 결과 조회
 * <p>
 * {@link IssueResultReadRepository}의 QueryDSL 조회 조각이다. 구현은 {@code out}의 {@code IssueResultReadRepositoryCustomImpl}이다.
 */
public interface IssueResultReadRepositoryCustom {

    /** 쿠폰의 발급 요청 결과 전부. 행 수는 쿠폰 수량(쿠폰별 상한) 안이다 */
    List<IssueResultRow> findRowsByCouponId(long couponId);
}
