package com.hapbang.coupon.app.port;

import java.util.Collection;
import java.util.List;

import com.hapbang.coupon.domain.RecoveryIssueResult;

/**
 * 발급 요청 결과 조회. 모두 쿠폰 스키마 안의 테이블만 읽는다
 * <p>
 * {@link CouponIssueRequestRepository}의 QueryDSL 조회 조각이다. 구현은 {@code out}의 {@code CouponIssueRequestRepositoryCustomImpl}이다.
 */
public interface CouponIssueRequestRepositoryCustom {

    /** {@code issueRequestIds} 가운데 DB에 결과가 있는 요청 ID. 재발행 배치와 복구가 쓴다 */
    List<String> findExistingRequestIds(Long couponId, Collection<String> issueRequestIds);

    /** 쿠폰의 DB 확정 발급 요청 결과 전부(복구 재구축 입력, 처리 epoch 포함). 저장 순서 */
    List<RecoveryIssueResult> findResults(Long couponId);

    /** 대사용 쿠폰의 발급 요청 결과 전부. 행 수는 쿠폰 수량(쿠폰별 상한) 안이다 */
    List<IssueResultRow> findResultRows(long couponId);
}
