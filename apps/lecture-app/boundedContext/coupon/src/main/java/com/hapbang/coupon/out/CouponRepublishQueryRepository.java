package com.hapbang.coupon.out;

import org.springframework.context.annotation.Primary;
import org.springframework.data.repository.Repository;

import com.hapbang.coupon.domain.CouponIssueRequest;

/**
 * 재발행 배치의 읽기(읽기 전용). 쓰기와 단건 조회는 {@link CouponRepository}·{@link CouponIssueRequestRepository}가 맡는다.
 * 조회는 {@link CouponRepublishQueryRepositoryCustom}(QueryDSL)이 구현한다. {@code @Primary}는 조회 계약({@code CouponRepublishReader})
 * 주입이 같은 계약을 구현한 QueryDSL 구현체 빈이 아닌 이 저장소를 고르게 한다.
 */
@Primary
public interface CouponRepublishQueryRepository extends Repository<CouponIssueRequest, Long>,
        CouponRepublishQueryRepositoryCustom {
}
