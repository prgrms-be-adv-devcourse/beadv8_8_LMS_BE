package com.hapbang.chat.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.SentChatMessage;

import lombok.RequiredArgsConstructor;

/**
 * Controller가 메시지 기능을 쓰는 진입점. Controller는 UseCase를 직접 쓰지 않고 이 Facade를 통한다.
 * 역할 검사({@code @PreAuthorize})는 여기서 한다(팀 규칙).
 */
@Component
@RequiredArgsConstructor
public class ChatMessageFacade {

    private final ChatMessageUseCase chatMessageUseCase;
    private final ChatMessageSupporter chatMessageSupporter;

    /**
     * STOMP로만 호출된다. STOMP 처리 스레드에는 Spring Security 인증 정보가 없어 {@code @PreAuthorize}를 붙이지 않는다.
     * 역할은 UseCase가 채팅 회원의 복제된 역할로 검사한다.
     */
    public SentChatMessage send(Long chatRoomId, Long senderId, String content, String clientMessageId) {
        return chatMessageUseCase.send(chatRoomId, senderId, content, clientMessageId);
    }

    @PreAuthorize(ChatRoles.CHAT)
    public ChatMessagePageResponse getMessages(Long chatRoomId, Long userId, Long cursor, int size) {
        return chatMessageSupporter.getMessages(chatRoomId, userId, cursor, size);
    }
}
