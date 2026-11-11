package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 쿠폰 계산·예약 상한 설정 ({@code hapbang.coupon.calculate.*}). 값을 주지 않으면 코드 기본값이다.
 * <p>
 * <b>모두 측정·합의 전 값(플레이스홀더)이다.</b> 대표 주문(항목 수·보유 쿠폰 수)으로 계산 시간을 측정한 뒤 정한다.
 *
 * @param maxSearchNodes 추천 탐색이 방문할 최대 노드 수. 넘으면 찾은 최선을 최대 할인이 아닌 것({@code optimal=false})으로 돌려준다.
 *                       기본 100,000
 * @param maxItems       계산·예약 한 번의 최대 항목 수. 넘으면 400이다. 강의마다 강의 조회를 한 번 부르므로 그 비용의 상한이기도 하다.
 *                       기본 20
 */
@ConfigurationProperties("hapbang.coupon.calculate")
public record CouponCalculateProperties(Integer maxSearchNodes, Integer maxItems) {

    public CouponCalculateProperties {
        maxSearchNodes = maxSearchNodes == null ? 100_000 : maxSearchNodes;
        maxItems = maxItems == null ? 20 : maxItems;
        if (maxSearchNodes < 1) {
            throw new IllegalArgumentException("hapbang.coupon.calculate.max-search-nodes는 1 이상이어야 한다");
        }
        if (maxItems < 1) {
            throw new IllegalArgumentException("hapbang.coupon.calculate.max-items는 1 이상이어야 한다");
        }
    }

    /** 모두 기본값 */
    public static CouponCalculateProperties defaults() {
        return new CouponCalculateProperties(null, null);
    }
}
