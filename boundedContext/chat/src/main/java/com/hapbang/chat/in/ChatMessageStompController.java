package com.hapbang.chat.in;

import java.security.Principal;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import com.hapbang.chat.app.ChatMessageFacade;
import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.domain.ChatErrorCode;
import com.hapbang.chat.domain.ChatException;
import com.hapbang.chat.in.dto.ChatErrorMessage;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.websocket.ChatMessageSender;
import com.hapbang.chat.in.websocket.ChatWebSocketConfig;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 클라이언트가 /pub/chats/{chatRoomId}/messages로 보내면 DB에 저장한 뒤
 * 현재 참여자에게 /user/queue/chats/messages로 전달하고, 받는 사람에게 새 메시지 알림을 보낸다.
 */
@Controller
@RequiredArgsConstructor
public class ChatMessageStompController {

    private final ChatMessageFacade chatMessageFacade;
    private final ChatMessageSender chatMessageSender;

    @MessageMapping("/chats/{chatRoomId}/messages")
    public void sendMessage(@DestinationVariable("chatRoomId") Long chatRoomId,
            @Valid @Payload SendChatMessageRequest request, Principal principal) {
        Long senderId = Long.valueOf(principal.getName());
        SentChatMessage sent = chatMessageFacade.send(chatRoomId, senderId, request.content());

        chatMessageSender.deliver(sent.message(), sent.deliverToUserIds());
        chatMessageSender.notifyNewMessage(sent.message(), sent.notifyUserIds());
    }

    @MessageExceptionHandler(ChatException.class)
    @SendToUser(destinations = ChatWebSocketConfig.ERROR_QUEUE, broadcast = false)
    public ChatErrorMessage handleChatException(ChatException exception) {
        return new ChatErrorMessage(exception.getErrorCode().name(), exception.getMessage());
    }

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser(destinations = ChatWebSocketConfig.ERROR_QUEUE, broadcast = false)
    public ChatErrorMessage handleInvalidPayload(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult() == null
                ? ChatErrorCode.INVALID_REQUEST.getMessage()
                : exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getDefaultMessage())
                        .findFirst()
                        .orElse(ChatErrorCode.INVALID_REQUEST.getMessage());
        return new ChatErrorMessage(ChatErrorCode.INVALID_REQUEST.name(), message);
    }
}
