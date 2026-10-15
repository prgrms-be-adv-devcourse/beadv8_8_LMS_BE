package com.hapbang.chat.in.websocket;

import java.security.Principal;
import java.util.Set;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * STOMP 연결·구독·보내기를 검사한다 (#235).
 * <ul>
 *     <li>CONNECT·STOMP(연결 프레임): 웹소켓 핸드셰이크의 로그인 세션 사용자({@link Principal})로 회원을 식별한다.
 *     핸드셰이크 요청도 global SecurityConfig를 거치므로, 로그인하지 않으면 연결 자체가 401로 막힌다.
 *     {@code Principal.getName()}은 회원 ID다(회원 모듈의 OAuth2 로그인 사용자).
 *     세션 역할이 채팅 역할(인플루언서·인증된 인플루언서·관리자)이 아니면 거절한다. 로그인 이후 강등·정지는
 *     UseCase가 채팅 회원의 복제된 역할로 다시 막는다.</li>
 *     <li>SUBSCRIBE: 자기 회원 큐(/user/...)만 허용한다.</li>
 *     <li>SEND: 애플리케이션 목적지(/pub/...)만 허용한다. 브로커(/queue/...)나 다른 회원 큐(/user/{회원 ID}/...)로 직접 보내면
 *     서버 검증 없이 상대에게 전달되므로(위조 메시지) 막는다.</li>
 * </ul>
 * 거절하면 Spring이 ERROR 프레임을 보내고 연결을 PROTOCOL_ERROR로 닫는다.
 * ERROR 프레임은 닫기와 겹치면 도착하지 않을 수 있으므로, 프론트는 ERROR 수신과 연결 종료를 모두 거절로 처리한다(#173).
 */
@Component
public class ChatStompInterceptor implements ChannelInterceptor {

    /** 채팅을 쓸 수 있는 세션 역할. Facade의 {@code ChatRoles.CHAT}과 같다 */
    private static final Set<String> CHAT_AUTHORITIES = Set.of("ROLE_INFLUENCER", "ROLE_VERIFIED_INFLUENCER",
            "ROLE_ADMIN");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            // STOMP 1.2의 STOMP 프레임도 Spring은 CONNECT와 같은 연결로 처리한다. 둘 다 검사해야 우회되지 않는다
            case CONNECT, STOMP -> checkChatUser(accessor.getUser());
            case SUBSCRIBE -> requireDestination(accessor.getDestination(), ChatWebSocketConfig.USER_PREFIX + "/",
                    "구독할 수 없는 경로입니다: ");
            case SEND -> requireDestination(accessor.getDestination(), ChatWebSocketConfig.APP_PREFIX + "/",
                    "보낼 수 없는 경로입니다: ");
            default -> {
            }
        }
        return message;
    }

    private void checkChatUser(Principal user) {
        if (user == null) {
            throw new MessageDeliveryException("로그인이 필요합니다.");
        }
        try {
            Long.valueOf(user.getName());
        } catch (NumberFormatException exception) {
            throw new MessageDeliveryException("로그인 회원 정보가 올바르지 않습니다.");
        }
        if (!(user instanceof Authentication authentication) || authentication.getAuthorities().stream()
                .noneMatch(authority -> CHAT_AUTHORITIES.contains(authority.getAuthority()))) {
            throw new MessageDeliveryException("인플루언서 이상 또는 관리자만 채팅을 사용할 수 있습니다.");
        }
    }

    private static void requireDestination(String destination, String prefix, String errorMessage) {
        if (destination == null || !destination.startsWith(prefix)) {
            throw new MessageDeliveryException(errorMessage + destination);
        }
    }
}
