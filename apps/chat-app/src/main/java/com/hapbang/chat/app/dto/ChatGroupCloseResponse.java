package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;

import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.domain.ChatRoomStatusReason;

/**
 * 단체방 폐쇄 결과.
 *
 * @param historyExpiresAt 참여자 조회 만료 시각(전환 시각 + 30×24시간). 이 시각부터 볼 수 없다
 */
public record ChatGroupCloseResponse(Long chatRoomId, ChatRoomStatus status, ChatRoomStatusReason statusReason,
        LocalDateTime readOnlyAt, LocalDateTime historyExpiresAt) {

    public static ChatGroupCloseResponse from(ChatRoom room) {
        return new ChatGroupCloseResponse(room.getId(), room.getStatus(), room.getStatusReason(), room.getReadOnlyAt(),
                room.getHistoryExpiresAt());
    }
}
