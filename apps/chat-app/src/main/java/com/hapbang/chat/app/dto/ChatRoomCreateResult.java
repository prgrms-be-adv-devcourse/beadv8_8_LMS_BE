package com.hapbang.chat.app.dto;

/**
 * @param room    저장 트랜잭션이 커밋된 뒤 조립한 방 응답
 * @param created 새로 만든 방이면 true, 기존 방을 재사용했으면 false
 */
public record ChatRoomCreateResult(ChatRoomResponse room, boolean created) {
}
