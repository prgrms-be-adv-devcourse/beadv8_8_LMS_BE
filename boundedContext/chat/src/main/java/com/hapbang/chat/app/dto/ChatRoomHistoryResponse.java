package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.chat.domain.ChatRoomStatus;

/**
 * 관리자 분쟁 확인용 채팅방 기록. Soft Delete 된 메시지도 포함한다.
 */
public record ChatRoomHistoryResponse(
        Long chatRoomId,
        ChatRoomStatus status,
        LocalDateTime createdAt,
        List<ChatRoomMemberResponse> members,
        List<ChatRoomLogResponse> logs,
        List<ChatMessageHistoryResponse> messages
) {
}
