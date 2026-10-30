package com.hapbang.chat.out.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 채팅이 소유하는 Redis Stream·consumer group·Pub/Sub 채널·접속 정보 키 이름. 환경 namespace를 앞에 붙인다.
 * 계약: {@code apps/chat-app/docs/contracts/realtime-fanout.md}
 */
@Component
public class ChatRedisKeys {

    public static final String DELIVERY_GROUP = "chat-delivery-v1";
    public static final String CONTROL_GROUP = "chat-control-v1";

    private final String namespace;

    public ChatRedisKeys(@Value("${hapbang.chat.redis.namespace:local}") String namespace) {
        Assert.isTrue(StringUtils.hasText(namespace) && !StringUtils.containsWhitespace(namespace),
                "hapbang.chat.redis.namespace는 공백 없는 값이어야 합니다.");
        this.namespace = namespace;
    }

    public String deliveryStream() {
        return namespace + ":chat:delivery:stream:v1";
    }

    public String controlStream() {
        return namespace + ":chat:control:stream:v1";
    }

    /** 방 메시지 채널. 그 방을 연 로컬 세션이 있는 앱만 구독한다 */
    public String roomChannel(long roomId) {
        return roomChannelPrefix() + roomId + ":v1";
    }

    /** 방 채널 이름의 앞부분. 받은 채널 이름이 방 채널인지 가릴 때 쓴다 */
    public String roomChannelPrefix() {
        return namespace + ":chat:room:";
    }

    /**
     * 방 채널 이름에서 방 ID를 읽는다.
     *
     * @return 방 채널이 아니면 null
     */
    public Long roomIdOf(String channel) {
        String prefix = roomChannelPrefix();
        if (!channel.startsWith(prefix) || !channel.endsWith(":v1")) {
            return null;
        }
        try {
            return Long.valueOf(channel.substring(prefix.length(), channel.length() - ":v1".length()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 서버 채널: 새 메시지 알림 재료. 모든 앱이 늘 구독하며, 발행 구독자 수가 자기 구독 단절 신호다 */
    public String activityChannel() {
        return namespace + ":chat:activity:v1";
    }

    /** 서버 채널: 연결 제어 */
    public String controlChannel() {
        return namespace + ":chat:control:v1";
    }

    /** 접속 중인 회원(ZSET, 원소 회원 ID, 점수 만료 시각 ms) */
    public String presenceOnline() {
        return namespace + ":chat:presence:online";
    }

    /** 한 회원의 연결(ZSET, 원소 {@code 앱ID:세션ID}, 점수 만료 시각 ms) */
    public String presenceUser(long userId) {
        return namespace + ":chat:presence:user:" + userId;
    }
}
