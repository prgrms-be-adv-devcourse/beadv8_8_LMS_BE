package com.hapbang.chat.in.websocket;

import java.util.List;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.in.dto.ChatNotification;

import lombok.RequiredArgsConstructor;

/**
 * 커밋이 끝난 메시지를 STOMP로 전달한다. 방 단위 topic이 아니라 서비스가 확인한 현재 참여자에게만 회원별로 보낸다.
 */
@Component
@RequiredArgsConstructor
public class ChatMessageSender {

    private final SimpMessagingTemplate messagingTemplate;

    public void deliver(ChatMessageResponse message, List<Long> userIds) {
        for (Long userId : userIds) {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), ChatWebSocketConfig.MESSAGE_QUEUE,
                    message);
        }
    }

    public void notifyNewMessage(ChatMessageResponse message, List<Long> userIds) {
        ChatNotification notification = ChatNotification.newMessage(message);
        for (Long userId : userIds) {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), ChatWebSocketConfig.NOTIFICATION_QUEUE,
                    notification);
        }
    }
}
