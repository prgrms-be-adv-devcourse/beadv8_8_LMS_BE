package com.hapbang.chat.in.websocket;

import java.util.List;

import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.in.dto.ChatNotification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 커밋이 끝난 메시지를 STOMP로 전달한다. 방 단위 topic이 아니라 UseCase가 확인한 현재 참여자에게만 회원별로 보낸다.
 * 그래서 퇴장한 회원의 열린 구독이 남아 있어도 이후 메시지를 받지 못한다.
 * <p>
 * 전달 실패는 전송 실패가 아니다. 메시지는 이미 저장됐으므로 보낸 사람에게 실패로 알리지 않고(다시 보내면 중복 저장된다)
 * 기록만 남긴 뒤 나머지 회원에게 계속 보낸다. 받지 못한 회원은 메시지 이력 조회로 확인한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageSender {

    private final SimpMessagingTemplate messagingTemplate;

    public void deliver(ChatMessageResponse message, List<Long> userIds) {
        for (Long userId : userIds) {
            send(userId, ChatWebSocketConfig.MESSAGE_QUEUE, message, message.messageId());
        }
    }

    public void notifyNewMessage(ChatMessageResponse message, List<Long> userIds) {
        ChatNotification notification = ChatNotification.newMessage(message);
        for (Long userId : userIds) {
            send(userId, ChatWebSocketConfig.NOTIFICATION_QUEUE, notification, message.messageId());
        }
    }

    private void send(Long userId, String destination, Object payload, Long messageId) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), destination, payload);
        } catch (MessagingException exception) {
            log.error("채팅 전달 실패 userId={}, destination={}, messageId={}", userId, destination, messageId,
                    exception);
        }
    }
}
