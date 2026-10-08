/**
 * 채팅 도메인. 모든 테이블은 {@code chat} 스키마에 둔다.
 * <ul>
 *     <li>chat_user: 회원 정보 복제본</li>
 *     <li>chat_room: 1:1 채팅방 (status, direct_key, last_sequence)</li>
 *     <li>chat_room_member: 참여자</li>
 *     <li>chat_message: 메시지 (room_sequence, message_type)</li>
 *     <li>chat_room_log: 방 이벤트 기록 (INSERT만)</li>
 * </ul>
 */
// TODO(db): 운영 DB에 chat 스키마와 테이블을 만드는 방식이 정해지지 않았다(ddl-auto 미설정, 마이그레이션 도구 미도입).
//  팀 방식이 정해지면 위 테이블, 유일 제약(chat_room.direct_key, (chat_room_id, user_id), (chat_room_id, room_sequence))과
//  인덱스를 반영한다.
package com.hapbang.chat.domain;
