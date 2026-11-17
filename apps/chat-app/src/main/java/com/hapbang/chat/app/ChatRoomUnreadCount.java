package com.hapbang.chat.app;

/** 방별 안 읽은 메시지 수 ({@link ChatMessageReader#countUnread}) */
public record ChatRoomUnreadCount(Long chatRoomId, long unreadCount) {
}
