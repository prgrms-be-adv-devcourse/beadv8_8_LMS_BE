package com.hapbang.auxiliary.notification.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import com.hapbang.auxiliary.notification.app.dto.ConnectionTicket;
import com.hapbang.shared.member.domain.Role;

/**
 * 알림 STOMP 수신 검사(ND-026·ND-027). 결합하지 않은 연결의 CONNECT, 클라이언트 SEND, 자기 목적지가 아닌 구독(끝에 / 붙인 것 포함)을
 * 거절하고, 결합한 연결의 그 밖의 프레임(UNSUBSCRIBE·ACK)은 받는다.
 */
class NotificationStompInterceptorTest {

    private static final String BOUND = "bound-session";
    private static final String UNBOUND = "unbound-session";

    private final NotificationWebSocketSessions sessions = new NotificationWebSocketSessions(Duration.ofMinutes(5));
    private final NotificationStompInterceptor interceptor = new NotificationStompInterceptor(sessions);

    NotificationStompInterceptorTest() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(NotificationTicketHandshakeInterceptor.CONNECTION_ATTRIBUTE,
                new ConnectionTicket(1L, Role.USER, Instant.now(), Instant.now().plus(Duration.ofMinutes(15))));
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(BOUND);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        sessions.decorate(mock(WebSocketHandler.class)).afterConnectionEstablished(session);
    }

    @AfterEach
    void tearDown() {
        sessions.destroy();
    }

    @Test
    void 결합하지_않은_연결의_CONNECT는_거절한다() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, UNBOUND, "1", null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void 결합한_회원과_다른_사용자의_CONNECT는_거절한다() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, BOUND, "2", null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void 결합한_회원의_CONNECT는_받는다() {
        assertThat(interceptor.preSend(frame(StompCommand.CONNECT, BOUND, "1", null), null)).isNotNull();
    }

    @Test
    void SEND는_결합한_연결이어도_거절한다() {
        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SEND, BOUND, "1", "/user/queue/notifications"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void 자기_알림_목적지와_다른_구독은_끝에_슬래시만_붙어도_거절한다() {
        for (String destination : new String[] {"/user/queue/notifications/", "/user/2/queue/notifications",
                "/queue/notifications", "/user/queue/notifications?x"}) {
            assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, BOUND, "1", destination), null))
                    .as(destination).isInstanceOf(MessageDeliveryException.class);
        }
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, BOUND, "1", "/user/queue/notifications"), null))
                .isNotNull();
    }

    @Test
    void 결합한_연결의_UNSUBSCRIBE와_ACK는_받고_결합하지_않은_연결은_거절한다() {
        assertThat(interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, BOUND, "1", null), null)).isNotNull();
        assertThat(interceptor.preSend(frame(StompCommand.ACK, BOUND, "1", null), null)).isNotNull();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, UNBOUND, "1", null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void DISCONNECT는_언제나_통과한다() {
        assertThat(interceptor.preSend(frame(StompCommand.DISCONNECT, UNBOUND, null, null), null)).isNotNull();
    }

    private static Message<byte[]> frame(StompCommand command, String sessionId, String user, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        if (user != null) {
            accessor.setUser(new PreAuthenticatedAuthenticationToken(user, null));
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
