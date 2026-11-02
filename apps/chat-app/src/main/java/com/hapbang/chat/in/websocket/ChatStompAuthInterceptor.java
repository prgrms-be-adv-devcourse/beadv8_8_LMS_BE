package com.hapbang.chat.in.websocket;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * STOMP 인증 확인(설계 문서 6장). 회원은 핸드셰이크의 연결 티켓으로 이미 정해졌다({@link ChatConnectionTicketHandshakeInterceptor},
 * {@link ChatHandshakeHandler}, 연결이 열릴 때 {@link ChatWebSocketSessionRegistry}에 결합). 이 인터셉터는 수신 채널의 첫 인터셉터로,
 * 회원을 쓰는 다른 검사({@link ChatStompInterceptor})보다 먼저 돈다. 인터셉터는 연결별 처리 줄({@link ChatInboundReceiveBudget})에
 * 넣기 전 웹소켓 수신 스레드에서 프레임을 받은 순서대로 돈다.
 * <ul>
 *     <li>CONNECT·STOMP: 연결에 결합한 회원이 없거나 STOMP 사용자가 그 회원이 아니면 {@link ChatStompAuthenticationException}
 *     (ERROR {@code code: UNAUTHENTICATED} + 연결 종료)이다. 티켓 없이 열린 연결은 핸드셰이크에서 이미 거절되므로 방어 검사다.
 *     CONNECT의 {@code Authorization} 헤더는 보지 않는다. 같은 연결의 두 번째 CONNECT·STOMP는 거절한다.</li>
 *     <li>그 밖의 프레임(SUBSCRIBE·UNSUBSCRIBE·SEND 등)은 회원을 결합한 연결에서만 받는다.</li>
 *     <li>연결 만료가 지났는데 아직 닫히지 않은 연결의 프레임은 처리하지 않고 그 연결을
 *     {@link ChatWebSocketSessionRegistry#TOKEN_EXPIRED}로 닫는다(ERROR로 닫으면 FE가 만료 사유를 받지 못한다).</li>
 *     <li>DISCONNECT(클라이언트가 보낸 것과 연결 종료 정리)는 언제나 통과한다. 하트비트는 명령이 없어 검사하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class ChatStompAuthInterceptor implements ChannelInterceptor {

    private final ChatWebSocketSessionRegistry chatWebSocketSessionRegistry;
    private final Clock clock;

    @Autowired
    public ChatStompAuthInterceptor(ChatWebSocketSessionRegistry chatWebSocketSessionRegistry) {
        this(chatWebSocketSessionRegistry, Clock.systemUTC());
    }

    /** 시계를 직접 넘긴다(테스트) */
    ChatStompAuthInterceptor(ChatWebSocketSessionRegistry chatWebSocketSessionRegistry, Clock clock) {
        this.chatWebSocketSessionRegistry = chatWebSocketSessionRegistry;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        return switch (accessor.getCommand()) {
            // STOMP 1.2의 STOMP 프레임도 Spring은 CONNECT와 같은 연결로 처리한다. 둘 다 검사해야 우회되지 않는다
            case CONNECT, STOMP -> connect(accessor) ? message : null;
            case DISCONNECT -> message;
            default -> acceptsFrame(accessor.getSessionId()) ? message : null;
        };
    }

    private boolean connect(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        Long userId = chatWebSocketSessionRegistry.userIdOf(sessionId);
        Principal user = accessor.getUser();
        if (userId == null || user == null || !String.valueOf(userId).equals(user.getName())) {
            log.info("회원을 결합하지 않은 연결의 STOMP CONNECT를 거절함 sessionId={}", sessionId);
            throw new ChatStompAuthenticationException();
        }
        if (!chatWebSocketSessionRegistry.markStompConnected(sessionId)) {
            throw new MessageDeliveryException("이미 CONNECT한 연결입니다.");
        }
        return acceptsFrame(sessionId);
    }

    /**
     * @return 처리해도 되면 true. 만료가 지나 연결을 닫았거나 닫는 중이면 false(프레임을 버린다)
     * @throws ChatStompAuthenticationException 회원을 결합하지 않은 연결이면
     */
    private boolean acceptsFrame(String sessionId) {
        Instant expiresAt = chatWebSocketSessionRegistry.authenticatedUntil(sessionId);
        if (expiresAt == null) {
            throw new ChatStompAuthenticationException();
        }
        if (expiresAt.isAfter(clock.instant())) {
            return true;
        }
        // 그 사이 재인증으로 만료가 늦춰졌으면 닫지 않고 받는다. 만료 확인과 닫기는 재인증과 같은 잠금 안에서 한다
        return !chatWebSocketSessionRegistry.closeIfExpired(sessionId);
    }
}
