package com.hapbang.chat.out;

/** 방별 안 읽은 메시지 수 ({@link ChatMessageRepository#countUnread}) */
public record ChatRoomUnreadCount(Long chatRoomId, long unreadCount) {
}
