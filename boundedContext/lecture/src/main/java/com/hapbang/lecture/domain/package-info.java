/**
 * 강의 도메인. 모든 테이블은 {@code lectures} 스키마에 둔다.
 * <ul>
 *     <li>lecture: 강의 (status, rejection_reason, inspection_requested_at, inspected_at, inspected_by, version)</li>
 *     <li>lecture_content: 강의 영상·자료 (file_id, sequence, content_type)</li>
 *     <li>cart: 회원별 장바구니 (user_id 유일)</li>
 *     <li>cart_item: 장바구니에 담은 강의 (cart_id, lecture_id, deleted_at)</li>
 * </ul>
 */
// 테이블은 Flyway 마이그레이션(src/main/resources/db/migration/)으로 만든다. 엔티티를 바꾸면 마이그레이션도 추가한다.
//  - lecture_content의 순서 유일성(살아 있는 행끼리 (lecture_id, sequence) 중복 금지)은 MVP에서 DB 제약 없이
//    Lecture 애그리거트(매번 1부터 다시 매김)와 lecture.version 낙관적 락으로 보장한다(D12).
//    DB 제약이 필요해지면 부분 유일 인덱스가 아니라 지연 검사 제약을 쓴다. 콘텐츠 교체 시 Hibernate가 새 행 INSERT를
//    기존 행 Soft Delete UPDATE보다 먼저 실행하므로, 즉시 검사하는 부분 유일 인덱스는 교체를 항상 실패시킨다.
//    EXCLUDE USING btree (lecture_id WITH =, sequence WITH =) WHERE (deleted_at IS NULL) DEFERRABLE INITIALLY DEFERRED
//  - cart_item은 부분 유일 인덱스 uk_cart_item_cart_lecture (cart_id, lecture_id) WHERE deleted_at IS NULL로
//    살아 있는 같은 강의를 하나만 둔다(D18). 같은 트랜잭션에서 빼고 다시 담으면 위와 같은 이유로 걸린다.
package com.hapbang.lecture.domain;
