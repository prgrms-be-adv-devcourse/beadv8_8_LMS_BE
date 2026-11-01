package com.hapbang.chat.in.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;

import com.hapbang.chat.app.ChatRealtimeFacade;
import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.RoomActivityFanout;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.out.redis.ChatRedisKeys;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 구독 진입 어댑터의 payload 해석(realtime-fanout.md 예시 JSON)과 재구독 감지를 검증한다. 처리는 실행기에서 비동기로 돈다.
 */
class ChatRealtimeRedisListenerTest {

    private static final long TIMEOUT_MILLIS = 2_000;

    private final ChatRedisKeys keys = new ChatRedisKeys("test-listener");
    private final ChatRealtimeFacade facade = mock(ChatRealtimeFacade.class);
    private final ChatRealtimeRedisListener listener = new ChatRealtimeRedisListener(facade,
            JsonMapper.builder().build(), keys, 1, 10, 1, 10, Duration.ofSeconds(1), new SimpleMeterRegistry());

    @AfterEach
    void tearDown() {
        listener.destroy();
    }

    @Test
    void 계약_예시의_방_채널_payload를_읽어_Facade에_넘긴다() {
        String deliveryId = UUID.randomUUID().toString();
        String json = """
                {
                  "deliveryId": "%s",
                  "type": "MESSAGE",
                  "roomId": 12,
                  "recipientSetVersion": 7,
                  "message": { "messageId": 501, "chatRoomId": 12, "roomSequence": 88, "senderUserId": 3, "messageType": "TEXT", "content": "안녕", "sentAt": "2026-10-29T10:00:00", "clientMessageId": null, "notificationKind": null, "referenceId": null, "parentReferenceId": null },
                  "notifyRule": "RECIPIENTS_EXCEPT_SENDER",
                  "senderClientMessageId": "c-123"
                }
                """.formatted(deliveryId);

        listener.onMessage(message(keys.roomChannel(12), json), null);

        ArgumentCaptor<MessageFanout> captor = ArgumentCaptor.forClass(MessageFanout.class);
        verify(facade, timeout(TIMEOUT_MILLIS)).deliverMessage(captor.capture());
        MessageFanout fanout = captor.getValue();
        assertThat(fanout.deliveryId()).isEqualTo(UUID.fromString(deliveryId));
        assertThat(fanout.type()).isEqualTo(MessageFanout.Type.MESSAGE);
        assertThat(fanout.roomId()).isEqualTo(12L);
        assertThat(fanout.recipientSetVersion()).isEqualTo(7);
        assertThat(fanout.message().messageId()).isEqualTo(501L);
        assertThat(fanout.message().roomSequence()).isEqualTo(88L);
        assertThat(fanout.message().messageType()).isEqualTo(ChatMessageType.TEXT);
        assertThat(fanout.message().sentAt()).isEqualTo(LocalDateTime.of(2026, 10, 29, 10, 0));
        assertThat(fanout.message().clientMessageId()).isNull();
        assertThat(fanout.notifyRule()).isEqualTo(ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER);
        assertThat(fanout.senderClientMessageId()).isEqualTo("c-123");
    }

    @Test
    void 서버_채널의_알림_재료를_읽어_Facade에_넘긴다() {
        String json = """
                { "deliveryId": "%s", "roomId": 12, "recipientSetVersion": 7, "roomSequence": 88, "notifyRule": "RECIPIENTS_EXCEPT_SENDER", "senderUserId": 3, "lastMessage": { "messageId": 501, "chatRoomId": 12, "roomSequence": 88, "senderUserId": 3, "messageType": "TEXT", "content": "안녕", "sentAt": "2026-10-29T10:00:00", "clientMessageId": null, "notificationKind": null, "referenceId": null, "parentReferenceId": null } }
                """.formatted(UUID.randomUUID());

        listener.onMessage(message(keys.activityChannel(), json), null);

        ArgumentCaptor<RoomActivityFanout> captor = ArgumentCaptor.forClass(RoomActivityFanout.class);
        verify(facade, timeout(TIMEOUT_MILLIS)).onRoomActivity(captor.capture());
        assertThat(captor.getValue().roomId()).isEqualTo(12L);
        assertThat(captor.getValue().recipientSetVersion()).isEqualTo(7);
        assertThat(captor.getValue().lastMessage().messageId()).isEqualTo(501L);
    }

    @Test
    void 읽을_수_없는_payload는_버리고_다음_제어_작업은_처리한다() {
        String controlId = UUID.randomUUID().toString();

        listener.onMessage(message(keys.controlChannel(), "{not json"), null);
        listener.onMessage(message(keys.controlChannel(), """
                { "controlId": "%s", "type": "RECHECK_USER", "userId": 7, "memberVersion": 5, "reason": "MEMBER_MODIFIED" }
                """.formatted(controlId)), null);

        verify(facade, timeout(TIMEOUT_MILLIS)).applyControl(new ControlFanout(UUID.fromString(controlId),
                ChatControlType.RECHECK_USER, 7L, 5L, ChatControlReason.MEMBER_MODIFIED));
        verify(facade, after(200).times(1)).applyControl(any());
    }

    @Test
    void 구독하지_않은_채널의_메시지는_무시한다() {
        listener.onMessage(message("other:chat:room:12:v1", "{}"), null);

        verify(facade, after(300).never()).deliverMessage(any());
        verify(facade, never()).applyControl(any());
    }

    @Test
    void 처리_중_예외가_나도_다음_작업을_계속_처리한다() {
        doThrow(new IllegalStateException("처리 실패")).doNothing().when(facade).applyControl(any());
        String json = """
                { "controlId": "%s", "type": "RECHECK_USER", "userId": 7, "memberVersion": null, "reason": "MEMBER_MODIFIED" }
                """.formatted(UUID.randomUUID());

        listener.onMessage(message(keys.controlChannel(), json), null);
        listener.onMessage(message(keys.controlChannel(), json), null);

        verify(facade, timeout(TIMEOUT_MILLIS).times(2)).applyControl(any());
    }

    @Test
    void 서버_채널별_첫_구독_확인은_무시하고_같은_채널의_다음_구독_확인에서만_복구_처리를_한다() {
        byte[] fanoutChannel = bytes(keys.activityChannel());
        byte[] controlChannel = bytes(keys.controlChannel());

        listener.onChannelSubscribed(fanoutChannel, 1);
        listener.onChannelSubscribed(controlChannel, 2);
        verify(facade, after(300).never()).onMessageSubscriptionRecovered();
        verify(facade, never()).onControlSubscriptionRecovered();

        // Lettuce 재연결 뒤 재구독
        listener.onChannelSubscribed(fanoutChannel, 1);
        listener.onChannelSubscribed(controlChannel, 2);
        verify(facade, timeout(TIMEOUT_MILLIS)).onMessageSubscriptionRecovered();
        verify(facade, timeout(TIMEOUT_MILLIS)).onControlSubscriptionRecovered();

        // 다음 단절·재구독에도 다시 한 번씩 처리한다
        listener.onChannelSubscribed(fanoutChannel, 1);
        verify(facade, timeout(TIMEOUT_MILLIS).times(2)).onMessageSubscriptionRecovered();
        verify(facade, after(200).times(1)).onControlSubscriptionRecovered();
    }

    private static DefaultMessage message(String channel, String body) {
        return new DefaultMessage(bytes(channel), bytes(body));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void 방_채널의_구독_확인은_해제_뒤_다시_더해도_재구독으로_보지_않는다() {
        byte[] roomChannel = bytes(keys.roomChannel(12));

        listener.onChannelSubscribed(roomChannel, 3);
        listener.onChannelSubscribed(roomChannel, 3);

        verify(facade, after(300).never()).onMessageSubscriptionRecovered();
    }
}
