package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;

import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;

public record ChatRoomLogResponse(Long logId, Long actorUserId, ChatRoomEventType eventType,
        LocalDateTime occurredAt) {

    public static ChatRoomLogResponse from(ChatRoomLog log) {
        return new ChatRoomLogResponse(log.getId(), log.getActorUserId(), log.getEventType(), log.getOccurredAt());
    }
}
