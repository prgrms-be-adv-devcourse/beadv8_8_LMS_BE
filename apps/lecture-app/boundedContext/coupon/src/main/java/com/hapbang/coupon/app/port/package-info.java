/**
 * 쿠폰 app이 바깥(Redis 발급 저장소, 강의 조회 내부 API, 프로모션 예산, DB 조회)에 요구하는 계약. 구현은 {@code out}에 둔다.
 * <p>
 * DB 조회 계약({@code XxxReader}, {@link com.hapbang.coupon.app.port.RecoveryLookup})은 {@code out}의
 * {@code XxxRepositoryCustom}이 이어받고 {@code XxxRepositoryCustomImpl}이 QueryDSL로 구현한다. 그 결과 값(record)도 이 패키지에 둔다.
 */
package com.hapbang.coupon.app.port;
