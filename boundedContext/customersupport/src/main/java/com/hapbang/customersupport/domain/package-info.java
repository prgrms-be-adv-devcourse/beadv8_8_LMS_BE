/**
 * 고객센터 도메인. 모든 테이블은 {@code customersupport} 스키마에 둔다.
 * <ul>
 *     <li>inquiry: 회원의 1:1 문의</li>
 * </ul>
 */
// 스키마는 Flyway 마이그레이션(src/main/resources/db/migration/V*__create_customersupport_*.sql)으로 만든다.
// 엔티티를 바꾸면 같은 PR에 마이그레이션을 추가한다(docs/db-migration.md).
package com.hapbang.customersupport.domain;
