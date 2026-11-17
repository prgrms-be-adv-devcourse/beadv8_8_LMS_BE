/**
 * 쿠폰 app이 바깥(Redis 발급 저장소, 강의 조회 내부 API, 프로모션 예산, DB)에 요구하는 계약. 구현은 {@code out}에 둔다.
 * <p>
 * DB 계약은 {@code XxxRepository}다. Spring Data Commons {@code Repository}만 상속하고(JpaRepository를 상속하지 않는다), 쓰는 기본
 * 메서드(save·findById 등)와 메서드 이름 쿼리, {@code @Query}(JPQL·native)·{@code @Modifying}·{@code @Lock}을 여기에 선언한다. 구현은
 * Spring Data JPA가 붙인다. QueryDSL 조회는 {@code XxxRepositoryCustom}에 선언하고 {@code out}의 {@code XxxRepositoryCustomImpl}이
 * 구현한다. 서비스는 {@code XxxRepository}를 주입받는다. 저장소가 돌려주는 결과 값(record)도 이 패키지에 둔다.
 */
package com.hapbang.coupon.app.port;
