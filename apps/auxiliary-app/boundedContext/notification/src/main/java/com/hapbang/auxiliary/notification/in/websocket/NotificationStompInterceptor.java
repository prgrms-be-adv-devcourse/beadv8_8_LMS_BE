package com.hapbang.auxiliary.notification.in.websocket;

import java.security.Principal;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.ConditionalOnNotificationRealtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 알림 STOMP 수신 검사(ND-026·ND-027). 알림 연결은 서버가 보내기만 하는 통로다.
 * <ul>
 *     <li>CONNECT·STOMP: 티켓으로 결합한 회원이 있고 STOMP 사용자가 그 회원일 때만 받는다.</li>
 *     <li>SUBSCRIBE: 자기 알림 목적지 {@value #DESTINATION}만 받는다. 다른 회원의 사용자 목적지·브로커 목적지는 거절한다.</li>
 *     <li>SEND: 언제나 거절한다. 클라이언트가 브로커·다른 회원의 목적지에 프레임을 넣지 못하게 한다.</li>
 *     <li>연결 만료가 지났는데 아직 닫히지 않은 연결의 프레임은 버리고 연결을 닫는다(ND-056).</li>
 *     <li>DISCONNECT는 늘 통과한다. 하트비트는 명령이 없어 검사하지 않는다.</li>
 * </ul>
 * 거절은 예외로 알린다. Spring이 ERROR 프레임을 보내고 연결을 닫는다.
 */
@Slf4j
@Component
@ConditionalOnNotificationRealtime
@RequiredArgsConstructor
public class NotificationStompInterceptor implements ChannelInterceptor {

    /** 클라이언트가 구독하는 자기 알림 목적지 */
    public static final String DESTINATION = "/user/queue/notifications";

    private final NotificationWebSocketSessions sessions;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        String sessionId = accessor.getSessionId();
        return switch (accessor.getCommand()) {
            case DISCONNECT -> message;
            case CONNECT, STOMP -> {
                requireBoundUser(sessionId, accessor.getUser());
                yield sessions.closeIfExpired(sessionId) ? null : message;
            }
            case SUBSCRIBE -> {
                requireBoundUser(sessionId, accessor.getUser());
                if (!DESTINATION.equals(accessor.getDestination())) {
                    log.info("알림 목적지가 아닌 구독을 거절함 sessionId={}", sessionId);
                    throw new MessageDeliveryException("구독할 수 없는 목적지입니다.");
                }
                yield sessions.closeIfExpired(sessionId) ? null : message;
            }
            case SEND -> {
                log.info("알림 연결의 SEND를 거절함 sessionId={}", sessionId);
                throw new MessageDeliveryException("알림 연결은 보낼 수 없습니다.");
            }
            default -> {
                requireBoundUser(sessionId, accessor.getUser());
                yield sessions.closeIfExpired(sessionId) ? null : message;
            }
        };
    }

    private void requireBoundUser(String sessionId, Principal user) {
        Long userId = sessions.userIdOf(sessionId);
        if (userId == null || user == null || !String.valueOf(userId).equals(user.getName())) {
            log.info("회원을 결합하지 않은 알림 연결의 프레임을 거절함 sessionId={}", sessionId);
            throw new MessageDeliveryException("인증되지 않은 연결입니다.");
        }
    }
}
