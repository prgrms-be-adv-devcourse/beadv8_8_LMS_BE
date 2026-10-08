package com.hapbang.chat.app;

import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.SentChatMessage;

import lombok.RequiredArgsConstructor;

/**
 * Controller가 메시지 기능을 쓰는 진입점. Controller는 Service를 직접 쓰지 않고 이 Facade를 통한다.
 */
@Component
@RequiredArgsConstructor
public class ChatMessageFacade {

    private final ChatMessageService chatMessageService;

    public SentChatMessage send(Long chatRoomId, Long senderId, String content) {
        return chatMessageService.send(chatRoomId, senderId, content);
    }

    public ChatMessagePageResponse getMessages(Long chatRoomId, Long userId, Long cursor, int size) {
        return chatMessageService.getMessages(chatRoomId, userId, cursor, size);
    }
}
