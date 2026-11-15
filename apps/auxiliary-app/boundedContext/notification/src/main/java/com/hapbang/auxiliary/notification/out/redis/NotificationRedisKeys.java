package com.hapbang.auxiliary.notification.out.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.ConditionalOnNotificationRealtime;

/**
 * 알림 모듈의 Redis 키·채널 이름. 접두어는 {@code hapbang.notification.realtime.redis-prefix}(기본 {@code notification})다.
 * 같은 Redis를 채팅 등 다른 앱과 함께 쓰므로 접두어로 나눈다. 시험은 컨텍스트마다 다른 접두어를 쓴다.
 */
@Component
@ConditionalOnNotificationRealtime
public class NotificationRedisKeys {

    private final String prefix;

    public NotificationRedisKeys(@Value("${hapbang.notification.realtime.redis-prefix:notification}") String prefix) {
        this.prefix = prefix;
    }

    /** 연결 티켓. 키에는 티켓 원문이 아니라 SHA-256(hex)을 쓴다 */
    public String connectionTicket(String ticketHash) {
        return prefix + ":ws-ticket:" + ticketHash;
    }

    /** 회원별 티켓 발급 횟수(고정 창) */
    public String connectionTicketRate(long userId) {
        return prefix + ":ws-ticket-rate:" + userId;
    }

    /** 실시간 프레임 Pub/Sub 채널. 모든 서버가 구독하고 받는 사람 연결이 있는 서버만 보낸다 */
    public String realtimeChannel() {
        return prefix + ":realtime";
    }
}
