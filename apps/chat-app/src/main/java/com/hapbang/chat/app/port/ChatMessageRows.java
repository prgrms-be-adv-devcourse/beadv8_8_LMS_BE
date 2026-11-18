package com.hapbang.chat.app.port;

import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatUser;

/**
 * {@link ChatMessageRepositoryCustom} 조회 결과 묶음.
 */
public final class ChatMessageRows {

    private ChatMessageRows() {
    }

    /**
     * 메시지와 발신자 복제본.
     *
     * @param sender 발신자가 없거나(시스템 안내) 복제본이 없으면 null
     */
    public record MessageWithSender(ChatMessage message, ChatUser sender) {
    }
}
