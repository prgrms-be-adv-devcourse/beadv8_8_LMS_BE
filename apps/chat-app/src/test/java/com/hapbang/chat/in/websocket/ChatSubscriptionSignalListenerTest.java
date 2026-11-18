package com.hapbang.chat.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.event.DefaultEventListenerFactory;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.NativeMessageHeaderAccessor;

import com.hapbang.chat.app.dto.ChatSubscriptionReason;
import com.hapbang.chat.app.dto.ChatSubscriptionSignal;
import com.hapbang.chat.app.dto.ChatSubscriptionSignalEvent;
import com.hapbang.chat.app.port.LocalChatSender.RoomTarget;

/**
 * 거절·회수 이벤트를 받으면 그 구독에 프레임을 쓴다. 프레임은 송신기를 바로 부른 것과 헤더·payload가 같고, 이벤트를 낸 스레드에서
 * 바로 쓰이며, 전달 실패는 이벤트를 낸 쪽으로 알리지 않는다.
 */
class ChatSubscriptionSignalListenerTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final MessageChannel clientOutboundChannel = mock(MessageChannel.class);
    private final StompLocalChatSender sender = new StompLocalChatSender(messagingTemplate, clientOutboundChannel);
    private final ChatSubscriptionSignalListener listener = new ChatSubscriptionSignalListener(sender);

    @BeforeEach
    void setUp() {
        when(messagingTemplate.getMessageConverter()).thenReturn(new JacksonJsonMessageConverter());
        when(clientOutboundChannel.send(any())).thenReturn(true);
    }

    @Test
    void 거절_이벤트는_송신기를_바로_부른_것과_같은_프레임을_쓴다() {
        sender.sendSubscriptionSignal(12L, new RoomTarget("s-1", "sub-a", 1),
                ChatSubscriptionSignal.SUBSCRIPTION_REJECTED, ChatSubscriptionReason.STALE_PARTICIPATION, 2);
        listener.onSignal(new ChatSubscriptionSignalEvent(12L, "s-1", "sub-a", 1,
                ChatSubscriptionSignal.SUBSCRIPTION_REJECTED, ChatSubscriptionReason.STALE_PARTICIPATION, 2));

        List<Message<?>> sent = captureOutbound(2);
        assertSameFrame(sent.get(1), sent.get(0));
        assertThat(nativeHeaders(sent.get(1)))
                .containsExactly(Map.entry(StompLocalChatSender.FRAME_TYPE_HEADER, List.of("SUBSCRIPTION_REJECTED")));
    }

    @Test
    void 회수_이벤트는_송신기를_바로_부른_것과_같은_프레임을_쓴다() {
        sender.sendSubscriptionSignal(12L, new RoomTarget("s-2", "sub-b", 3),
                ChatSubscriptionSignal.SUBSCRIPTION_REVOKED, ChatSubscriptionReason.LEFT, null);
        listener.onSignal(new ChatSubscriptionSignalEvent(12L, "s-2", "sub-b", 3,
                ChatSubscriptionSignal.SUBSCRIPTION_REVOKED, ChatSubscriptionReason.LEFT, null));

        List<Message<?>> sent = captureOutbound(2);
        assertSameFrame(sent.get(1), sent.get(0));
        assertThat(new String((byte[]) sent.get(1).getPayload()))
                .isEqualTo("{\"type\":\"SUBSCRIPTION_REVOKED\",\"roomId\":12,\"reason\":\"LEFT\"}");
    }

    /** 운영처럼 컨텍스트의 이벤트 발행으로 보내면, 발행한 스레드에서 발행이 돌아오기 전에 프레임이 쓰이고 전달 실패는 던지지 않는다 */
    @Test
    void 컨텍스트_이벤트로_보내면_같은_스레드에서_바로_쓰고_전달_실패는_알리지_않는다() {
        Thread publisher = Thread.currentThread();
        when(clientOutboundChannel.send(any())).thenAnswer(invocation -> {
            assertThat(Thread.currentThread()).isSameAs(publisher);
            throw new MessageDeliveryException("송신 대기열이 가득 참");
        });
        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.registerSingleton(EventListenerMethodProcessor.class.getName(), EventListenerMethodProcessor.class);
            context.registerSingleton("eventListenerFactory", DefaultEventListenerFactory.class);
            context.getBeanFactory().registerSingleton("chatSubscriptionSignalListener", listener);
            context.refresh();

            assertThatNoException().isThrownBy(() -> context.publishEvent(new ChatSubscriptionSignalEvent(12L, "s-1",
                    "sub-a", 1, ChatSubscriptionSignal.SUBSCRIPTION_REJECTED, ChatSubscriptionReason.NOT_MEMBER,
                    null)));
        }

        verify(clientOutboundChannel).send(any());
    }

    private List<Message<?>> captureOutbound(int count) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Message<?>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel, times(count)).send(captor.capture());
        return captor.getAllValues();
    }

    /** 헤더(메시지 ID·시각 제외)와 payload 바이트가 같다 */
    private static void assertSameFrame(Message<?> actual, Message<?> expected) {
        assertThat(actual.getPayload()).isEqualTo(expected.getPayload());
        assertThat(withoutIdAndTimestamp(actual.getHeaders())).isEqualTo(withoutIdAndTimestamp(expected.getHeaders()));
    }

    private static Map<String, Object> withoutIdAndTimestamp(MessageHeaders headers) {
        Map<String, Object> copy = new HashMap<>(headers);
        copy.remove(MessageHeaders.ID);
        copy.remove(MessageHeaders.TIMESTAMP);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> nativeHeaders(Message<?> sent) {
        return (Map<String, List<String>>) sent.getHeaders().get(NativeMessageHeaderAccessor.NATIVE_HEADERS);
    }
}
