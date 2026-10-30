package com.hapbang.chat.in.websocket;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.domain.ChatMessageType;

class ChatMessageSenderTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final ChatMessageSender chatMessageSender = new ChatMessageSender(messagingTemplate);

    private final ChatMessageResponse message = new ChatMessageResponse(10L, 1L, 1L, HOST_ID, ChatMessageType.TEXT,
            "안녕하세요", NOW, "client-1", null, null, null, null);

    @Test
    void 한_회원에게_전달이_실패해도_예외를_던지지_않고_나머지_회원에게_보낸다() {
        doThrow(new MessageDeliveryException("전달 실패")).when(messagingTemplate)
                .convertAndSendToUser(eq(String.valueOf(HOST_ID)), anyString(), any(Object.class));

        assertThatCode(() -> chatMessageSender.deliver(message, List.of(HOST_ID, GUEST_ID)))
                .doesNotThrowAnyException();

        verify(messagingTemplate).convertAndSendToUser(String.valueOf(GUEST_ID), ChatWebSocketConfig.MESSAGE_QUEUE,
                message);
    }

    @Test
    void 알림_전달이_실패해도_예외를_던지지_않는다() {
        doThrow(new MessageDeliveryException("전달 실패")).when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> chatMessageSender.notifyNewMessage(message, List.of(GUEST_ID)))
                .doesNotThrowAnyException();
    }
}
