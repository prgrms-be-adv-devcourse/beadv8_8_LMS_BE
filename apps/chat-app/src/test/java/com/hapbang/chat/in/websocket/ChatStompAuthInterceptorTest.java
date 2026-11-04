package com.hapbang.chat.in.websocket;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import com.hapbang.chat.app.dto.ChatConnectionTicket;
import com.hapbang.shared.member.domain.Role;

/**
 * STOMP 인증 확인 인터셉터(설계 문서 6장): 회원은 핸드셰이크의 연결 티켓으로 정해지고, CONNECT는 결합한 회원이 없으면 거절한다.
 * 인증 전 프레임 거절, 두 번째 CONNECT 거절, 만료 뒤 프레임을 본다.
 */
class ChatStompAuthInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(15));
    private static final String SESSION_ID = "session-1";

    private final MutableClock clock = new MutableClock();
    private final ChatWebSocketSessionRegistry registry = new ChatWebSocketSessionRegistry(absent(), absent(),
            idleScheduler(), clock, Duration.ofSeconds(30));
    private final ChatStompAuthInterceptor interceptor = new ChatStompAuthInterceptor(registry, clock);
    private final MessageChannel channel = mock(MessageChannel.class);
    private final WebSocketHandler handler = registry.decorate(mock(WebSocketHandler.class));

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void 연결_티켓으로_회원을_결합한_연결의_CONNECT는_통과한다(StompCommand command) throws Exception {
        open(true);
        Message<byte[]> connect = message(frame(command, () -> String.valueOf(HOST_ID)));

        assertThat(interceptor.preSend(connect, channel)).isSameAs(connect);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void 회원을_결합하지_않은_연결의_CONNECT는_사용자가_있어도_UNAUTHENTICATED로_거절한다(StompCommand command)
            throws Exception {
        open(false);
        // 핸드셰이크 요청의 사용자(transport Principal)가 있어도 티켓으로 결합하지 않았으면 회원으로 보지 않는다
        StompHeaderAccessor accessor = frame(command, () -> String.valueOf(HOST_ID));

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                .isExactlyInstanceOf(ChatStompAuthenticationException.class)
                .hasMessage("인증이 필요합니다.");
    }

    @Test
    void STOMP_사용자가_없거나_결합한_회원과_다르면_CONNECT를_거절한다() throws Exception {
        open(true);

        assertThatThrownBy(() -> interceptor.preSend(message(frame(StompCommand.CONNECT, null)), channel))
                .isExactlyInstanceOf(ChatStompAuthenticationException.class);
        assertThatThrownBy(() -> interceptor.preSend(message(frame(StompCommand.CONNECT,
                () -> String.valueOf(GUEST_ID))), channel))
                .isExactlyInstanceOf(ChatStompAuthenticationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void 같은_연결의_두_번째_CONNECT와_STOMP는_거절한다(StompCommand command) throws Exception {
        open(true);
        interceptor.preSend(message(frame(StompCommand.CONNECT, () -> String.valueOf(HOST_ID))), channel);

        assertThatThrownBy(() -> interceptor.preSend(message(frame(command, () -> String.valueOf(HOST_ID))), channel))
                .isExactlyInstanceOf(MessageDeliveryException.class);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"SEND", "SUBSCRIBE", "UNSUBSCRIBE", "ACK", "NACK", "BEGIN",
            "COMMIT", "ABORT"})
    void 회원을_결합하지_않은_연결의_프레임은_거절한다(StompCommand command) throws Exception {
        open(false);

        assertThatThrownBy(() -> interceptor.preSend(message(frame(command, () -> String.valueOf(HOST_ID))), channel))
                .isExactlyInstanceOf(ChatStompAuthenticationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"SEND", "SUBSCRIBE", "UNSUBSCRIBE"})
    void 회원을_결합한_연결의_프레임은_그대로_넘긴다(StompCommand command) throws Exception {
        open(true);
        Message<byte[]> frame = message(frame(command, () -> String.valueOf(HOST_ID)));

        assertThat(interceptor.preSend(frame, channel)).isSameAs(frame);
    }

    @Test
    void 만료가_지났는데_아직_닫히지_않은_연결의_프레임은_처리하지_않고_TOKEN_EXPIRED로_닫는다() throws Exception {
        WebSocketSession socket = open(true);
        clock.now = EXPIRES_AT;

        assertThat(interceptor.preSend(message(frame(StompCommand.SEND, () -> String.valueOf(HOST_ID))), channel))
                .isNull();
        assertThat(interceptor.preSend(message(frame(StompCommand.CONNECT, () -> String.valueOf(HOST_ID))), channel))
                .isNull();

        verify(socket, atLeastOnce()).close(ChatWebSocketSessionRegistry.TOKEN_EXPIRED);
    }

    @Test
    void DISCONNECT와_하트비트는_인증_없이_통과한다() {
        Message<byte[]> disconnect = message(frame(StompCommand.DISCONNECT, null));
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        heartbeat.setLeaveMutable(true);
        Message<byte[]> heartbeatMessage = MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders());

        assertThat(interceptor.preSend(disconnect, channel)).isSameAs(disconnect);
        assertThat(interceptor.preSend(heartbeatMessage, channel)).isSameAs(heartbeatMessage);
    }

    @Test
    void 인증_실패_ERROR_프레임에만_code_UNAUTHENTICATED를_붙인다() {
        ChatStompErrorHandler errorHandler = new ChatStompErrorHandler();

        Message<byte[]> unauthenticated = errorHandler.handleClientMessageProcessingError(null,
                new ChatStompAuthenticationException());
        Message<byte[]> otherRejection = errorHandler.handleClientMessageProcessingError(null,
                new MessageDeliveryException("보낼 수 없는 경로입니다: /queue/x"));

        StompHeaderAccessor first = StompHeaderAccessor.wrap(unauthenticated);
        assertThat(first.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(first.getFirstNativeHeader(ChatStompErrorHandler.CODE_HEADER)).isEqualTo("UNAUTHENTICATED");
        assertThat(first.getMessage()).isEqualTo("인증이 필요합니다.");
        StompHeaderAccessor second = StompHeaderAccessor.wrap(otherRejection);
        assertThat(second.getFirstNativeHeader(ChatStompErrorHandler.CODE_HEADER)).isNull();
        assertThat(second.getMessage()).isEqualTo("보낼 수 없는 경로입니다: /queue/x");
    }

    /** 웹소켓 연결을 연다. {@code withTicket}이면 핸드셰이크에서 쓴 연결 티켓이 세션 속성에 있다 */
    private WebSocketSession open(boolean withTicket) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        if (withTicket) {
            attributes.put(ChatConnectionTicketHandshakeInterceptor.CONNECTION_ATTRIBUTE,
                    new ChatConnectionTicket(HOST_ID, Role.INFLUENCER, NOW, EXPIRES_AT));
        }
        when(session.getId()).thenReturn(SESSION_ID);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        handler.afterConnectionEstablished(session);
        return session;
    }

    private static StompHeaderAccessor frame(StompCommand command, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(SESSION_ID);
        if (command == StompCommand.SEND || command == StompCommand.SUBSCRIBE) {
            accessor.setDestination(command == StompCommand.SEND ? "/pub/chats/1/messages" : "/user/queue/chats/sync");
        }
        if (command == StompCommand.SUBSCRIBE || command == StompCommand.UNSUBSCRIBE) {
            accessor.setSubscriptionId("sub-0");
        }
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private static Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static TaskScheduler idleScheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
        return scheduler;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        return mock(ObjectProvider.class);
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now = NOW;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
