package com.hapbang.chat.app;

/** 방별 안 읽은 메시지 수 ({@link ChatMessageRepositoryCustom#countUnread}) */
public record ChatRoomUnreadCount(Long chatRoomId, long unreadCount) {
}
