package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.chat.domain.ChatRoomStatus;

/**
 * 관리자 분쟁 확인용 채팅방 기록. 종료된 방, 참여자별로 숨겨진 메시지, Soft Delete 된 메시지도 포함한다.
 */
public record ChatRoomHistoryResponse(
        Long chatRoomId,
        ChatRoomStatus status,
        LocalDateTime createdAt,
        LocalDateTime endedAt,
        List<ChatRoomMemberResponse> members,
        List<ChatRoomLogResponse> logs,
        List<ChatMessageHistoryResponse> messages
) {
}
