/**
 * 강의 도메인. 모든 테이블은 {@code lectures} 스키마에 둔다.
 * <ul>
 *     <li>lecture: 강의 (status, rejection_reason, inspection_requested_at, inspected_at, inspected_by, version)</li>
 *     <li>lecture_content: 강의 영상·자료 (file_id, sequence, content_type)</li>
 * </ul>
 */
// 테이블은 Flyway 마이그레이션(src/main/resources/db/migration/)으로 만든다. 엔티티를 바꾸면 마이그레이션도 추가한다.
//  - lecture_content의 순서 유일성(살아 있는 행끼리 (lecture_id, sequence) 중복 금지)은 MVP에서 DB 제약 없이
//    Lecture 애그리거트(매번 1부터 다시 매김)와 lecture.version 낙관적 락으로 보장한다(D12).
//    DB 제약이 필요해지면 부분 유일 인덱스가 아니라 지연 검사 제약을 쓴다. 콘텐츠 교체 시 Hibernate가 새 행 INSERT를
//    기존 행 Soft Delete UPDATE보다 먼저 실행하므로, 즉시 검사하는 부분 유일 인덱스는 교체를 항상 실패시킨다.
//    EXCLUDE USING btree (lecture_id WITH =, sequence WITH =) WHERE (deleted_at IS NULL) DEFERRABLE INITIALLY DEFERRED
package com.hapbang.lecture.domain;
