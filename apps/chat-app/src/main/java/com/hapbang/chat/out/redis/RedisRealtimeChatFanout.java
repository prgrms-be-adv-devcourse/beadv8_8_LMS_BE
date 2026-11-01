package com.hapbang.chat.out.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatDeliveryUnavailableException;
import com.hapbang.chat.app.RealtimeChatFanout;
import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.RoomActivityFanout;
import com.hapbang.chat.global.config.ConditionalOnChatRealtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전달·제어 작업을 JSON으로 직렬화해 Redis Pub/Sub 채널(방 채널 {@link ChatRedisKeys#roomChannel}, 서버 채널
 * {@link ChatRedisKeys#activityChannel()}·{@link ChatRedisKeys#controlChannel()})에 발행한다. 계약: {@code apps/chat-app/docs/contracts/realtime-fanout.md}
 * <p>
 * 반환값은 {@code PUBLISH} 응답의 구독자 수 그대로다. 0도 바꾸지 않는다. replica 1개에서 0은 자기 구독 단절이므로
 * 호출한 쪽(전달 파이프라인)이 발행 완료로 기록하지 않고 멈췄다가 재확인 발행한다.
 * <p>
 * Redis 연결 불가(연결 거부·시간 초과·연결 끊김·LOADING)는 {@link ChatDeliveryUnavailableException}으로 바꿔 던진다. 전달 큐와 같은
 * 분류({@link RedisFailures})를 쓰며, 호출한 쪽은 시도 횟수를 늘리지 않고 파이프라인을 멈췄다가 연결이 돌아오면 이어 간다.
 * {@code PUBLISH}의 쓰기 거절({@code OOM}·{@code MISCONF}, {@link RedisFailures#isWriteRejected})은 예상하지 않지만 생기면 같은
 * 방식으로 다룬다(작업 실패가 아니라 Redis 상태 문제다).
 * 그 밖의 명령 오류·직렬화 오류는 그대로 던져 작업 실패로 센다.
 */
@Slf4j
@Component
@ConditionalOnChatRealtime
@RequiredArgsConstructor
public class RedisRealtimeChatFanout implements RealtimeChatFanout {

    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;
    private final ChatRedisKeys chatRedisKeys;

    /**
     * 서버 채널에 알림 재료를 먼저 발행하고, 구독자가 있으면 방 채널에 메시지를 발행한다(설계 Q25·Q26).
     * <ul>
     *     <li>서버 채널은 모든 앱이 늘 구독한다. 그 구독자 수가 자기 구독 단절 신호라 그대로 돌려준다. 0명이면 방 채널은 발행하지
     *     않는다(호출한 쪽이 멈췄다가 다시 시도한다).</li>
     *     <li>방 채널의 구독자 0명은 정상이다(그 방을 연 세션이 있는 앱이 없다). 명령이 성공하면 구독자 수와 관계없이 성공이다.</li>
     * </ul>
     */
    @Override
    public long publishMessage(MessageFanout fanout) {
        long subscribers = publish(chatRedisKeys.activityChannel(),
                jsonMapper.writeValueAsString(RoomActivityFanout.from(fanout)));
        if (subscribers <= 0) {
            log.debug("서버 채널 구독자 0명이라 방 채널을 발행하지 않음 deliveryId={}", fanout.deliveryId());
            return subscribers;
        }
        long roomSubscribers = publish(chatRedisKeys.roomChannel(fanout.roomId()), jsonMapper.writeValueAsString(fanout));
        log.debug("실시간 전달 작업 발행 deliveryId={}, roomId={}, servers={}, roomSubscribers={}", fanout.deliveryId(),
                fanout.roomId(), subscribers, roomSubscribers);
        return subscribers;
    }

    @Override
    public long publishControl(ControlFanout fanout) {
        long subscribers = publish(chatRedisKeys.controlChannel(), jsonMapper.writeValueAsString(fanout));
        log.debug("연결 제어 작업 발행 controlId={}, subscribers={}", fanout.controlId(), subscribers);
        return subscribers;
    }

    private long publish(String channel, String payload) {
        Long subscribers;
        try {
            subscribers = stringRedisTemplate.convertAndSend(channel, payload);
        } catch (RuntimeException exception) {
            if (RedisFailures.isConnectivityFailure(exception) || RedisFailures.isWriteRejected(exception)) {
                throw new ChatDeliveryUnavailableException("Redis에 발행할 수 없습니다. channel=" + channel, exception);
            }
            throw exception;
        }
        if (subscribers == null) {
            // 파이프라인·트랜잭션 안에서만 null이다. 발행 결과를 모르면 성공으로 보지 않는다
            throw new IllegalStateException("Redis PUBLISH 결과를 받지 못했습니다. channel=" + channel);
        }
        return subscribers;
    }
}
