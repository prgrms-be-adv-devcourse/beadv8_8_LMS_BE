package com.hapbang.chat.in.websocket;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import com.hapbang.chat.app.LocalChatSender;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatResyncReason;
import com.hapbang.chat.app.dto.ChatRoomActivity;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;
import com.hapbang.chat.in.dto.ChatNotification;
import com.hapbang.chat.in.dto.ChatSubscriptionFrame;
import com.hapbang.chat.in.dto.ChatSyncSignal;

import lombok.extern.slf4j.Slf4j;

/**
 * 수신 자격을 확인한 연결에 STOMP 프레임을 보낸다(resync-frame.md).
 * <ul>
 *     <li>방 topic({@code /topic/chats/rooms/{roomId}}): 브로커가 아니라 이 클래스가 구독마다 세션 ID·구독 ID 헤더를 붙여
 *     {@code clientOutboundChannel}에 쓴다(simple broker가 하는 방식과 같다). payload는 한 번만 직렬화한다.</li>
 *     <li>회원별 큐(전송 결과 확인·재동기화·새 메시지 알림)는 사용자 목적지로 보낸다. 새 메시지 알림은 세션 ID 헤더로 그 연결
 *     하나에만 보낸다.</li>
 * </ul>
 * 전달 실패는 전송 실패가 아니다. 메시지는 이미 저장됐으므로 보낸 사람에게 실패로 알리지 않고(다시 보내면 중복 저장된다) 기록만
 * 남긴다. 받지 못한 회원은 메시지 이력 조회로 확인한다.
 */
@Slf4j
@Component
public class ChatMessageSender implements LocalChatSender {

    /** 메시지 프레임의 받는 구독(또는 회원)의 참여 세대 헤더 */
    public static final String PARTICIPATION_VERSION_HEADER = "participation-version";
    /** 방 topic 구독으로 오는 프레임의 종류 헤더. 없으면 메시지다 */
    public static final String FRAME_TYPE_HEADER = "chat-frame-type";

    private final SimpMessagingTemplate messagingTemplate;
    private final MessageChannel clientOutboundChannel;

    public ChatMessageSender(SimpMessagingTemplate messagingTemplate,
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
        this.messagingTemplate = messagingTemplate;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @Override
    public void sendMessage(Long userId, ChatMessageResponse message, int participationVersion) {
        sendToUser(userId, ChatWebSocketConfig.MESSAGE_QUEUE, message,
                Map.of(PARTICIPATION_VERSION_HEADER, String.valueOf(participationVersion)));
    }

    @Override
    public void sendRoomMessage(Long roomId, ChatMessageResponse message, List<RoomTarget> targets) {
        Message<?> converted = convert(message);
        if (converted == null) {
            return;
        }
        for (RoomTarget target : targets) {
            writeToSubscription(roomId, target, converted, null);
        }
    }

    @Override
    public void sendSubscriptionSignal(Long roomId, RoomTarget target, SubscriptionSignal signal,
            ChatSubscriptionReason reason, Integer currentParticipationVersion) {
        Message<?> converted = convert(new ChatSubscriptionFrame(signal, roomId, reason, currentParticipationVersion));
        if (converted != null) {
            writeToSubscription(roomId, target, converted, signal.name());
        }
    }

    @Override
    public void sendRoomActivity(Long userId, String sessionId, ChatRoomActivity activity) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), ChatWebSocketConfig.NOTIFICATION_QUEUE,
                    ChatNotification.roomActivity(activity), accessor.getMessageHeaders());
        } catch (MessagingException exception) {
            log.error("새 메시지 알림 전달 실패 userId={}, sessionId={}, roomId={}", userId, sessionId,
                    activity.chatRoomId(), exception);
        }
    }

    @Override
    public void sendResync(Long userId, ChatResyncReason reason, LocalDateTime occurredAt) {
        sendToUser(userId, ChatWebSocketConfig.SYNC_QUEUE, ChatSyncSignal.resync(reason, occurredAt), null);
    }

    /** payload를 한 번 직렬화한다. 실패하면 기록하고 null */
    private Message<?> convert(Object payload) {
        try {
            Message<?> converted = messagingTemplate.getMessageConverter().toMessage(payload, null);
            if (converted == null) {
                log.error("방 topic 프레임을 직렬화하지 못함 type={}", payload.getClass().getSimpleName());
            }
            return converted;
        } catch (RuntimeException exception) {
            log.error("방 topic 프레임 직렬화 실패 type={}", payload.getClass().getSimpleName(), exception);
            return null;
        }
    }

    private void writeToSubscription(Long roomId, RoomTarget target, Message<?> converted, String frameType) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(target.sessionId());
        accessor.setSubscriptionId(target.subscriptionId());
        accessor.setDestination(ChatWebSocketConfig.ROOM_TOPIC_PREFIX + roomId);
        Object contentType = converted.getHeaders().get(MessageHeaders.CONTENT_TYPE);
        if (contentType instanceof MimeType mimeType) {
            accessor.setContentType(mimeType);
        }
        if (frameType == null) {
            accessor.setNativeHeader(PARTICIPATION_VERSION_HEADER, String.valueOf(target.participationVersion()));
        } else {
            accessor.setNativeHeader(FRAME_TYPE_HEADER, frameType);
        }
        try {
            clientOutboundChannel.send(MessageBuilder.createMessage(converted.getPayload(),
                    accessor.getMessageHeaders()));
        } catch (MessagingException exception) {
            log.error("방 topic 프레임 전달 실패 roomId={}, sessionId={}", roomId, target.sessionId(), exception);
        }
    }

    private void sendToUser(Long userId, String destination, Object payload, Map<String, Object> headers) {
        try {
            if (headers == null) {
                messagingTemplate.convertAndSendToUser(String.valueOf(userId), destination, payload);
            } else {
                messagingTemplate.convertAndSendToUser(String.valueOf(userId), destination, payload, headers);
            }
        } catch (MessagingException exception) {
            log.error("채팅 전달 실패 userId={}, destination={}", userId, destination, exception);
        }
    }
}
