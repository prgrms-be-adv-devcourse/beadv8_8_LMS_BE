package com.hapbang.chat.in.dto;

import com.hapbang.chat.domain.ChatMessage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param content         메시지 본문. 공백만 있으면 안 되고 {@link ChatMessage#MAX_CONTENT_LENGTH}자 이하 (#311)
 * @param clientMessageId 프론트가 붙이는 임시 ID(선택). 전달되는 메시지와 오류에 그대로 돌려줘서 어느 메시지의 결과인지 알 수 있게 한다
 */
public record SendChatMessageRequest(
        @NotBlank(message = "메시지 내용이 비어 있습니다.")
        @Size(max = ChatMessage.MAX_CONTENT_LENGTH,
                message = "메시지는 " + ChatMessage.MAX_CONTENT_LENGTH + "자 이하여야 합니다.")
        String content,
        @Size(max = 64, message = "clientMessageId는 64자 이하여야 합니다.")
        String clientMessageId
) {
}
