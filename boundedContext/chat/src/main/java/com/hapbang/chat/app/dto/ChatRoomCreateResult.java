package com.hapbang.chat.app.dto;

/**
 * @param created 새로 만든 방이면 true, 기존 방을 재사용했으면 false
 */
public record ChatRoomCreateResult(ChatRoomResponse room, boolean created) {
}
