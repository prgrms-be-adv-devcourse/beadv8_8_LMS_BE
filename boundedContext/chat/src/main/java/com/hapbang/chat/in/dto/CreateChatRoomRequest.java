package com.hapbang.chat.in.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateChatRoomRequest(
        @NotNull(message = "채팅 상대 회원 ID는 필수입니다.")
        @Positive(message = "채팅 상대 회원 ID는 양수여야 합니다.")
        Long targetUserId
) {
}
