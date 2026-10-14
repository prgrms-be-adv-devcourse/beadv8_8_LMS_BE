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

/**
 * STOMP 연결과 구독을 검사한다.
 * <ul>
 *     <li>CONNECT: 웹소켓 핸드셰이크의 로그인 세션 사용자({@link Principal})로 회원을 식별한다.
 *     핸드셰이크 요청도 global SecurityConfig를 거치므로, 로그인하지 않으면 연결 자체가 401로 막힌다.
 *     {@code Principal.getName()}은 회원 ID다(회원 모듈의 OAuth2 로그인 사용자).</li>
 *     <li>SUBSCRIBE: 자기 회원 큐(/user/...)만 허용한다. 거절하면 Spring이 ERROR 프레임을 보내고 연결을 PROTOCOL_ERROR로 닫는다.
 *     ERROR 프레임은 닫기와 겹치면 도착하지 않을 수 있으므로, 프론트는 ERROR 수신과 연결 종료를 모두 거절로 처리한다(#173).</li>
 * </ul>
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
            checkLoginUser(accessor.getUser());
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith(ChatWebSocketConfig.USER_PREFIX + "/")) {
                throw new MessageDeliveryException("구독할 수 없는 경로입니다: " + destination);
            }
        }
        return message;
    }

    private void checkLoginUser(Principal user) {
        if (user == null) {
            throw new MessageDeliveryException("로그인이 필요합니다.");
        }
        try {
            Long.valueOf(user.getName());
        } catch (NumberFormatException exception) {
            throw new MessageDeliveryException("로그인 회원 정보가 올바르지 않습니다.");
        }
    }
}
