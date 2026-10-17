package com.hapbang.chat.in;

import java.security.Principal;
import java.util.List;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;

import com.hapbang.chat.app.ChatMessageFacade;
import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.in.dto.ChatErrorMessage;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.websocket.ChatMessageSender;
import com.hapbang.chat.in.websocket.ChatWebSocketConfig;
import com.hapbang.global.exception.GlobalErrorCode;

import jakarta.validation.Valid;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 클라이언트가 /pub/chats/{chatRoomId}/messages로 보내면 DB에 저장한 뒤
 * 현재 참여자에게 /user/queue/chats/messages로 전달하고, 받는 사람에게 새 메시지 알림을 보낸다.
 * <p>
 * 보낼 때 붙인 {@code clientMessageId}는 보낸 사람에게 전달되는 메시지와 오류에 그대로 돌려준다. STOMP 전송에는 응답 확인이
 * 없어서, 프론트는 이 값으로 어느 메시지가 성공·실패했는지 구분한다. 같은 ID로 다시 보내면 새로 저장하지 않고 기존 메시지를
 * 보낸 사람에게만 다시 보낸다(#261).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatMessageStompController {

    private final ChatMessageFacade chatMessageFacade;
    private final ChatMessageSender chatMessageSender;

    @MessageMapping("/chats/{chatRoomId}/messages")
    public void sendMessage(@DestinationVariable("chatRoomId") Long chatRoomId,
            @Valid @Payload SendChatMessageRequest request, Principal principal) {
        Long senderId = Long.valueOf(principal.getName());
        // 빈 전송 ID는 보내지 않은 것으로 본다
        String clientMessageId = StringUtils.hasText(request.clientMessageId()) ? request.clientMessageId() : null;
        SentChatMessage sent;
        try {
            sent = chatMessageFacade.send(chatRoomId, senderId, request.content(), clientMessageId);
        } catch (RuntimeException exception) {
            throw new SendFailedException(exception, request.clientMessageId());
        }

        // 전송 ID는 보낸 사람에게만 담는다. 재전송이면 보낸 사람에게만 다시 가고 알림은 없다
        List<Long> others = sent.deliverToUserIds().stream().filter(userId -> !userId.equals(senderId)).toList();
        if (sent.deliverToUserIds().contains(senderId)) {
            chatMessageSender.deliver(sent.message().withClientMessageId(clientMessageId), List.of(senderId));
        }
        chatMessageSender.deliver(sent.message(), others);
        chatMessageSender.notifyNewMessage(sent.message(), sent.notifyUserIds());
    }

    /**
     * 전송 실패. 업무 오류는 오류 코드를, 예상하지 못한 오류는 내부 메시지 없이 INTERNAL_ERROR를 보낸다.
     */
    @MessageExceptionHandler(SendFailedException.class)
    @SendToUser(destinations = ChatWebSocketConfig.ERROR_QUEUE, broadcast = false)
    public ChatErrorMessage handleSendFailure(SendFailedException exception) {
        if (exception.getCause() instanceof ChatException chatException) {
            return new ChatErrorMessage(chatException.getErrorCode().getCode(), chatException.getMessage(),
                    exception.getClientMessageId());
        }
        log.error("채팅 메시지 전송 실패", exception.getCause());
        return new ChatErrorMessage(GlobalErrorCode.INTERNAL_ERROR.getCode(),
                GlobalErrorCode.INTERNAL_ERROR.getMessage(), exception.getClientMessageId());
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
        return new ChatErrorMessage(ChatErrorCode.INVALID_REQUEST.getCode(), message, clientMessageIdOf(exception));
    }

    /** 검증에 실패한 요청 본문에서 clientMessageId를 꺼낸다. 본문을 읽지 못했으면 null */
    private String clientMessageIdOf(MethodArgumentNotValidException exception) {
        if (exception.getBindingResult() != null
                && exception.getBindingResult().getTarget() instanceof SendChatMessageRequest request) {
            return request.clientMessageId();
        }
        return null;
    }

    /** 전송 실패 원인과 실패한 전송의 clientMessageId를 오류 처리기로 넘긴다 */
    @Getter
    static class SendFailedException extends RuntimeException {

        private final String clientMessageId;

        SendFailedException(RuntimeException cause, String clientMessageId) {
            super(cause);
            this.clientMessageId = clientMessageId;
        }
    }
}
