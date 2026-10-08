package com.hapbang.chat.in.dto;

import jakarta.validation.constraints.NotBlank;

public record SendChatMessageRequest(
        @NotBlank(message = "메시지 내용이 비어 있습니다.")
        String content
) {
}
