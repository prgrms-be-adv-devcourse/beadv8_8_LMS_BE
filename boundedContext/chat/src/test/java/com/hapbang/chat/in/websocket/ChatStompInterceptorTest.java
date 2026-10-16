package com.hapbang.chat.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.hapbang.chat.app.ChatUserFacade;

class ChatStompInterceptorTest {

    private final ChatUserFacade chatUserFacade = mock(ChatUserFacade.class);
    private final ChatStompInterceptor interceptor = new ChatStompInterceptor(chatUserFacade);
    private final MessageChannel channel = mock(MessageChannel.class);

    @ParameterizedTest
    @ValueSource(strings = {"/queue/chats/messages", "/topic/chats/1", "/user", "/users/queue/chats/messages"})
    void 회원_큐가_아닌_경로는_구독을_거절한다(String destination) {
        Message<byte[]> subscribe = subscribe(destination);

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel))
                .isExactlyInstanceOf(MessageDeliveryException.class)
                .hasMessage("구독할 수 없는 경로입니다: " + destination);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/user/queue/chats/messages", "/user/queue/chats/notifications",
            "/user/queue/chats/errors"})
    void 회원_큐는_구독할_수_있다(String destination) {
        Message<byte[]> subscribe = subscribe(destination);

        assertThat(interceptor.preSend(subscribe, channel)).isSameAs(subscribe);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void 채팅_역할이고_채팅_회원이_채팅할_수_있으면_연결을_허용한다(StompCommand command) {
        when(chatUserFacade.canChat(1L)).thenReturn(true);
        Message<byte[]> connect = connect(command, 1L, "ROLE_INFLUENCER");

        assertThat(interceptor.preSend(connect, channel)).isSameAs(connect);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void 채팅_회원이_채팅할_수_없으면_세션_역할이_인플루언서여도_연결을_거절한다(StompCommand command) {
        when(chatUserFacade.canChat(1L)).thenReturn(false);
        Message<byte[]> connect = connect(command, 1L, "ROLE_INFLUENCER");

        assertThatThrownBy(() -> interceptor.preSend(connect, channel))
                .isExactlyInstanceOf(MessageDeliveryException.class)
                .hasMessage("인플루언서 이상 또는 관리자만 채팅을 사용할 수 있습니다.");
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void 세션_역할이_채팅_역할이_아니면_연결을_거절한다(StompCommand command) {
        when(chatUserFacade.canChat(1L)).thenReturn(true);
        Message<byte[]> connect = connect(command, 1L, "ROLE_USER");

        assertThatThrownBy(() -> interceptor.preSend(connect, channel))
                .isExactlyInstanceOf(MessageDeliveryException.class);
    }

    private Message<byte[]> connect(StompCommand command, Long userId, String authority) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setUser(new UsernamePasswordAuthenticationToken(String.valueOf(userId), null,
                List.of(new SimpleGrantedAuthority(authority))));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribe(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("sub-0");
        // 인터셉터가 MessageHeaderAccessor.getAccessor로 같은 accessor를 꺼내도록 헤더를 변경 가능하게 둔다
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
