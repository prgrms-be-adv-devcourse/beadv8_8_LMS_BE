package com.hapbang.chat.in.websocket;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.NativeMessageHeaderAccessor;

import com.hapbang.chat.app.LocalChatSender.RoomTarget;
import com.hapbang.chat.app.LocalChatSender.SubscriptionSignal;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatResyncReason;
import com.hapbang.chat.app.dto.ChatRoomActivity;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.in.dto.ChatNotification;
import com.hapbang.chat.in.dto.ChatSyncSignal;

class ChatMessageSenderTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final MessageChannel clientOutboundChannel = mock(MessageChannel.class);
    private final ChatMessageSender chatMessageSender = new ChatMessageSender(messagingTemplate, clientOutboundChannel);

    private final ChatMessageResponse message = new ChatMessageResponse(10L, 1L, 1L, HOST_ID, ChatMessageType.TEXT,
            "안녕하세요", NOW, null, null, null, null);

    @BeforeEach
    void setUp() {
        when(messagingTemplate.getMessageConverter()).thenReturn(new JacksonJsonMessageConverter());
        when(clientOutboundChannel.send(any())).thenReturn(true);
    }

    @Test
    void 방_topic_프레임은_구독마다_세션_ID와_구독_ID로_쓰고_구독의_세대_헤더를_싣는다() {
        chatMessageSender.sendRoomMessage(12L, message,
                List.of(new RoomTarget("s-1", "sub-a", 1), new RoomTarget("s-2", "sub-b", 3)));

        List<Message<?>> sent = captureOutbound(2);
        assertFrame(sent.get(0), "s-1", "sub-a");
        assertThat(nativeHeader(sent.get(0), ChatMessageSender.PARTICIPATION_VERSION_HEADER)).isEqualTo("1");
        assertThat(nativeHeader(sent.get(1), ChatMessageSender.PARTICIPATION_VERSION_HEADER)).isEqualTo("3");
        assertThat(nativeHeader(sent.get(0), ChatMessageSender.FRAME_TYPE_HEADER)).isNull();
        // payload는 한 번 직렬화한 같은 바이트다
        assertThat(sent.get(0).getPayload()).isSameAs(sent.get(1).getPayload());
        assertThat(new String((byte[]) sent.get(0).getPayload(), StandardCharsets.UTF_8))
                .contains("\"messageId\":10").contains("\"roomSequence\":1");
    }

    @Test
    void 거절_회수_프레임은_그_구독에만_종류_헤더와_함께_쓴다() {
        chatMessageSender.sendSubscriptionSignal(12L, new RoomTarget("s-1", "sub-a", 1),
                SubscriptionSignal.SUBSCRIPTION_REJECTED, ChatSubscriptionReason.STALE_PARTICIPATION, 2);

        Message<?> sent = captureOutbound(1).getFirst();
        assertFrame(sent, "s-1", "sub-a");
        assertThat(nativeHeader(sent, ChatMessageSender.FRAME_TYPE_HEADER)).isEqualTo("SUBSCRIPTION_REJECTED");
        assertThat(nativeHeader(sent, ChatMessageSender.PARTICIPATION_VERSION_HEADER)).isNull();
        assertThat(new String((byte[]) sent.getPayload(), StandardCharsets.UTF_8))
                .isEqualTo("{\"type\":\"SUBSCRIPTION_REJECTED\",\"roomId\":12,\"reason\":\"STALE_PARTICIPATION\","
                        + "\"currentParticipationVersion\":2}");
    }

    @Test
    void 새_메시지_알림은_세션_ID_헤더로_그_연결에만_보낸다() {
        ChatRoomActivity activity = new ChatRoomActivity(12L, 2, 5L, 3, message);

        chatMessageSender.sendRoomActivity(GUEST_ID, "s-9", activity);

        ArgumentCaptor<MessageHeaders> headers = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messagingTemplate).convertAndSendToUser(eq(String.valueOf(GUEST_ID)),
                eq(ChatWebSocketConfig.NOTIFICATION_QUEUE), eq(ChatNotification.roomActivity(activity)),
                headers.capture());
        assertThat(SimpMessageHeaderAccessor.getSessionId(headers.getValue())).isEqualTo("s-9");
    }

    @Test
    void 전송_결과_확인에는_보낸_사람의_세대_헤더를_싣고_payload는_그대로_보낸다() {
        chatMessageSender.sendMessage(GUEST_ID, message, 3);

        verify(messagingTemplate).convertAndSendToUser(String.valueOf(GUEST_ID), ChatWebSocketConfig.MESSAGE_QUEUE,
                message, Map.of(ChatMessageSender.PARTICIPATION_VERSION_HEADER, "3"));
        assertThat(ChatMessageSender.PARTICIPATION_VERSION_HEADER).isEqualTo("participation-version");
    }

    @Test
    void 재동기화_신호는_회원별_sync_큐로_보낸다() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 10, 29, 10, 0);

        chatMessageSender.sendResync(GUEST_ID, ChatResyncReason.REALTIME_RESUBSCRIBED, occurredAt);

        assertThat(ChatWebSocketConfig.SYNC_QUEUE).isEqualTo("/queue/chats/sync");
        verify(messagingTemplate).convertAndSendToUser(String.valueOf(GUEST_ID), ChatWebSocketConfig.SYNC_QUEUE,
                new ChatSyncSignal(ChatSyncSignal.SignalType.RESYNC, ChatResyncReason.REALTIME_RESUBSCRIBED,
                        occurredAt));
    }

    @Test
    void 전달이_실패해도_예외를_던지지_않는다() {
        doThrow(new MessageDeliveryException("전달 실패")).when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class), anyMap());
        doThrow(new MessageDeliveryException("전달 실패")).when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class), any(MessageHeaders.class));
        doThrow(new MessageDeliveryException("전달 실패")).when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));
        doThrow(new MessageDeliveryException("전달 실패")).when(clientOutboundChannel).send(any());

        assertThatCode(() -> {
            chatMessageSender.sendMessage(GUEST_ID, message, 1);
            chatMessageSender.sendRoomMessage(12L, message, List.of(new RoomTarget("s-1", "sub-a", 1)));
            chatMessageSender.sendRoomActivity(GUEST_ID, "s-1", new ChatRoomActivity(12L, 1, 1L, 1, message));
            chatMessageSender.sendResync(GUEST_ID, ChatResyncReason.REALTIME_RESUBSCRIBED, NOW);
        }).doesNotThrowAnyException();
    }

    private List<Message<?>> captureOutbound(int count) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Message<?>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel, times(count)).send(captor.capture());
        return captor.getAllValues();
    }

    private static void assertFrame(Message<?> sent, String sessionId, String subscriptionId) {
        assertThat(SimpMessageHeaderAccessor.getMessageType(sent.getHeaders())).isEqualTo(SimpMessageType.MESSAGE);
        assertThat(SimpMessageHeaderAccessor.getSessionId(sent.getHeaders())).isEqualTo(sessionId);
        assertThat(SimpMessageHeaderAccessor.getSubscriptionId(sent.getHeaders())).isEqualTo(subscriptionId);
        assertThat(SimpMessageHeaderAccessor.getDestination(sent.getHeaders())).isEqualTo("/topic/chats/rooms/12");
    }

    @SuppressWarnings("unchecked")
    private static String nativeHeader(Message<?> sent, String name) {
        Map<String, List<String>> headers = (Map<String, List<String>>) sent.getHeaders()
                .get(NativeMessageHeaderAccessor.NATIVE_HEADERS);
        List<String> values = headers == null ? null : headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
