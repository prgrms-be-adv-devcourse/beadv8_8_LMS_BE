package com.hapbang.chat.in.websocket;

import java.security.Principal;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.hapbang.chat.in.ChatHeaders;

/**
 * CONNECT 시 X-User-Id 헤더로 회원을 식별한다. 구독은 자기 회원 큐(/user/...)만 허용한다.
 */
@Component
public class ChatStompInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (accessor.getCommand() == StompCommand.CONNECT) {
            // TODO(security): 로그인이 붙으면 핸드셰이크의 세션 인증 정보(Principal)를 쓰고 X-User-Id 헤더는 믿지 않는다.
            accessor.setUser(new ChatPrincipal(parseUserId(accessor.getFirstNativeHeader(ChatHeaders.USER_ID))));
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith(ChatWebSocketConfig.USER_PREFIX + "/")) {
                throw new MessageDeliveryException("구독할 수 없는 경로입니다: " + destination);
            }
        }
        return message;
    }

    private Long parseUserId(String value) {
        if (value == null) {
            throw new MessageDeliveryException(ChatHeaders.USER_ID + " 헤더가 필요합니다.");
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new MessageDeliveryException("회원 ID 형식이 올바르지 않습니다: " + value);
        }
    }

    record ChatPrincipal(Long userId) implements Principal {

        @Override
        public String getName() {
            return String.valueOf(userId);
        }
    }
}
