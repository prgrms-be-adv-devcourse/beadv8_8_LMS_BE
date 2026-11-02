package com.hapbang.chat.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatReceiptResponse;

import lombok.RequiredArgsConstructor;

/**
 * Controller가 수신 확인 기능을 쓰는 진입점. 역할 검사({@code @PreAuthorize})는 여기서 하고,
 * 처리는 {@link ChatReceiptService} 계약에 맡긴다.
 */
@Component
@RequiredArgsConstructor
public class ChatReceiptFacade {

    private final ChatReceiptService chatReceiptService;

    @PreAuthorize(ChatRoles.CHAT)
    public ChatReceiptResponse markReceived(Long chatRoomId, Long userId, int participationVersion,
            long receivedSequence) {
        return chatReceiptService.markReceived(chatRoomId, userId, participationVersion, receivedSequence);
    }
}
