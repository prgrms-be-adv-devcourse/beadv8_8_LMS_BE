package com.hapbang.coupon.app.port;

import org.springframework.data.repository.Repository;

import com.hapbang.coupon.domain.CouponIssueRequest;

/**
 * 복구·Redis 프로세스 점검이 읽는 조회(읽기 전용). 쓰기는 각 엔티티의 저장소가 한다. 모두 쿠폰 스키마 안의 테이블만 읽는다.
 * 조회는 {@link RecoveryLookupRepositoryCustom}(QueryDSL)이 맡는다.
 */
public interface RecoveryLookupRepository extends Repository<CouponIssueRequest, Long>, RecoveryLookupRepositoryCustom {
}
