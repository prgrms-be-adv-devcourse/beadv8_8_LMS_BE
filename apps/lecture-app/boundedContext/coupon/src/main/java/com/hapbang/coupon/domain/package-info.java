/**
 * 쿠폰 도메인: 엔티티와 순수 규칙(발행 검증, 상태 전이, 예산액, 만료 시각, Lua 반환값 해석, 발급 요청 상태 판정).
 * Spring에 의존하지 않는다. 시각은 호출자가 {@code Instant}로 넘긴다.
 */
package com.hapbang.coupon.domain;
