package com.hapbang.chat.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class ChatStompInterceptorTest {

    private final ChatStompInterceptor interceptor = new ChatStompInterceptor();
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

    private Message<byte[]> subscribe(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("sub-0");
        // 인터셉터가 MessageHeaderAccessor.getAccessor로 같은 accessor를 꺼내도록 헤더를 변경 가능하게 둔다
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
