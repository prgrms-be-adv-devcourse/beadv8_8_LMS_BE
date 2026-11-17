package com.hapbang.coupon.out;

import org.springframework.context.annotation.Primary;
import org.springframework.data.repository.Repository;

import com.hapbang.coupon.domain.CouponIssueRequest;

/**
 * 복구·Redis 프로세스 점검이 읽는 조회(읽기 전용). 쓰기는 각 엔티티의 저장소가 한다. 모두 쿠폰 스키마 안의 테이블만 읽는다.
 * 조회는 {@link RecoveryLookupRepositoryCustom}(QueryDSL)이 구현한다. {@code @Primary}는 조회 계약({@code RecoveryLookup}) 주입이
 * 같은 계약을 구현한 QueryDSL 구현체 빈이 아닌 이 저장소를 고르게 한다.
 */
@Primary
public interface RecoveryLookupRepository extends Repository<CouponIssueRequest, Long>, RecoveryLookupRepositoryCustom {
}
