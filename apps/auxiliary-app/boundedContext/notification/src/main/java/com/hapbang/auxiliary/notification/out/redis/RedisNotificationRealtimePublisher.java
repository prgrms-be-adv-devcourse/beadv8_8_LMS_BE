package com.hapbang.auxiliary.notification.out.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.ConditionalOnNotificationRealtime;
import com.hapbang.auxiliary.notification.app.NotificationRealtimePublisher;
import com.hapbang.auxiliary.notification.app.dto.NotificationFrame;
import com.hapbang.auxiliary.notification.app.dto.RealtimeMessage;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실시간 프레임을 Redis Pub/Sub 채널({@link NotificationRedisKeys#realtimeChannel()})에 JSON으로 발행한다(ND-027). 받는 사람 연결이 있는
 * 서버의 구독자가 그 연결로 보낸다. 구독자 수는 보지 않는다(받는 사람이 연결돼 있지 않으면 0이 정상이다).
 */
@Component
@ConditionalOnNotificationRealtime
@RequiredArgsConstructor
public class RedisNotificationRealtimePublisher implements NotificationRealtimePublisher {

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final NotificationRedisKeys keys;

    @Override
    public void publish(Long receiverUserId, NotificationFrame frame) {
        redis.convertAndSend(keys.realtimeChannel(),
                jsonMapper.writeValueAsString(new RealtimeMessage(receiverUserId, frame)));
    }
}
