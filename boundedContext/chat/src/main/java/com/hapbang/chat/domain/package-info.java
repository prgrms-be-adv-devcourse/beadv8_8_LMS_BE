/**
 * 채팅 도메인. 모든 테이블은 {@code chats} 스키마에 둔다.
 * <ul>
 *     <li>chat_user: 회원 정보 복제본</li>
 *     <li>chat_room: 1:1 채팅방 (status, direct_key, last_sequence, ended_at)</li>
 *     <li>chat_room_member: 참여자 (visible_from_sequence, participation_version)</li>
 *     <li>chat_message: 메시지 (room_sequence, message_type)</li>
 *     <li>chat_room_log: 방 이벤트 기록 (INSERT만)</li>
 * </ul>
 */
// 스키마는 Flyway 마이그레이션(src/main/resources/db/migration/V*__create_chat_tables.sql)으로 만든다.
// 엔티티를 바꾸면 같은 PR에 마이그레이션을 추가한다(docs/db-migration.md).
package com.hapbang.chat.domain;
