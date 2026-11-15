package com.hapbang.auxiliary.notification.in.redis;

import java.nio.charset.StandardCharsets;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.ConditionalOnNotificationRealtime;
import com.hapbang.auxiliary.notification.app.dto.RealtimeMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis Pub/Sub으로 받은 실시간 전달 작업을 이 서버에 있는 받는 사람 연결의 {@code /user/queue/notifications}로 보낸다(ND-027).
 * 받는 사람 연결이 이 서버에 없으면 메모리 브로커가 아무에게도 보내지 않는다. 읽을 수 없는 작업은 버린다(실시간 전달은 유실될 수 있다).
 */
@Slf4j
@Component(NotificationRealtimeRedisListener.BEAN_NAME)
@ConditionalOnNotificationRealtime
@RequiredArgsConstructor
public class NotificationRealtimeRedisListener implements MessageListener {

    public static final String BEAN_NAME = "notificationRealtimeRedisListener";
    static final String QUEUE = "/queue/notifications";

    private final JsonMapper jsonMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        RealtimeMessage realtime;
        try {
            realtime = jsonMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8),
                    RealtimeMessage.class);
        } catch (RuntimeException exception) {
            log.warn("읽을 수 없는 알림 실시간 작업을 버림", exception);
            return;
        }
        if (realtime.receiverUserId() == null || realtime.frame() == null) {
            log.warn("받는 사람이나 프레임이 없는 알림 실시간 작업을 버림");
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(realtime.receiverUserId()), QUEUE, realtime.frame());
        } catch (RuntimeException exception) {
            log.warn("알림 프레임을 연결로 보내지 못함 receiverUserId={}", realtime.receiverUserId(), exception);
        }
    }
}
