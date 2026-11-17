package com.hapbang.coupon.app.port;

import java.util.List;

/**
 * DLT 격리 기록 조회
 * <p>
 * {@link CouponDltLogRepository}의 QueryDSL 조회 조각이다. 구현은 {@code out}의 {@code CouponDltLogRepositoryCustomImpl}이다.
 */
public interface CouponDltLogRepositoryCustom {

    /**
     * 쿠폰의 아직 처리되지 않은 DLT 요청 ID들: PENDING과 REPLAYING(재발행 선점 중). REPLAYING은 선점 lease가 지났든 아니든 미처리로 센다
     * (전송 결과가 확정되기 전이므로). 재발행 배치의 제외와 복구의 미처리 집계가 쓴다.
     */
    List<String> findUnresolvedRequestIds(Long couponId);

    /** 아직 처리되지 않은 DLT 기록 전체 수(PENDING + REPLAYING, 쿠폰을 모르는 기록 포함) */
    long countUnresolved();
}
