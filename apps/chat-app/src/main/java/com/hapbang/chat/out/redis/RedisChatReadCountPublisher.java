package com.hapbang.chat.out.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatReadCountPublisher;
import com.hapbang.chat.app.dto.ReadChangedFanout;
import com.hapbang.chat.global.config.ConditionalOnChatRealtime;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 안 읽은 사람 수 변경 신호를 JSON으로 서버 채널 {@link ChatRedisKeys#readChannel()}에 발행한다. 구독자 수는 보지 않는다(최선 노력,
 * 자기 구독 단절은 알림 재료 채널이 알린다). 계약: {@code apps/chat-app/docs/contracts/unread-count.md}
 */
@Component
@ConditionalOnChatRealtime
@RequiredArgsConstructor
public class RedisChatReadCountPublisher implements ChatReadCountPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;
    private final ChatRedisKeys chatRedisKeys;

    @Override
    public void publish(ReadChangedFanout fanout) {
        stringRedisTemplate.convertAndSend(chatRedisKeys.readChannel(), jsonMapper.writeValueAsString(fanout));
    }
}
