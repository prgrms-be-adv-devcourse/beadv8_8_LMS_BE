package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;

import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;

/**
 * @param targetUserId 행위 대상 회원(단체방 초대·강퇴). 대상이 행위자 자신이거나 없으면 null
 */
public record ChatRoomLogResponse(Long logId, Long actorUserId, Long targetUserId, ChatRoomEventType eventType,
        LocalDateTime occurredAt) {

    public static ChatRoomLogResponse from(ChatRoomLog log) {
        return new ChatRoomLogResponse(log.getId(), log.getActorUserId(), log.getTargetUserId(), log.getEventType(),
                log.getOccurredAt());
    }
}
