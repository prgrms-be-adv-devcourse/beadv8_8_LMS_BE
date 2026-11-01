package com.hapbang.chat.app;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.hapbang.chat.app.dto.QueueGroupState;
import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 작업 큐 본문을 안전 경계 아래에서만 정리한다 (설계 4.5 "Redis 유실 복구와 보존").
 * <p>
 * 경계 = min(필수 그룹의 가장 앞선 미확인 위치(없으면 마지막 배분 위치), 보존 기간 시작 위치). 경계보다 앞선 항목만 지운다.
 * 그러므로 아직 배분되지 않은 항목·미확인 항목·보존 기간 안의 항목은 남는다. 길이(MAXLEN)로 자르지 않는다.
 * 큐나 필수 그룹이 없으면(유실·재생성 전) 정리하지 않는다. 격리(DEAD)는 ACK 전에 DB에 기록하므로 확인된 항목은 정리해도 된다.
 * 정리 대상은 Redis 전달 버퍼뿐이며 DB 원본·outbox·체크포인트는 지우지 않는다.
 */
@Slf4j
@Service
@ConditionalOnChatDeliveryPipeline
@RequiredArgsConstructor
public class ChatStreamTrimmer {

    private final ChatDeliveryQueue chatDeliveryQueue;
    private final ChatDeliveryProperties properties;
    private final ChatDeliveryAvailability availability;

    /** @return 지운 항목 수 */
    public long trim(ChatJobStream stream) {
        if (!availability.redisAvailable()) {
            return 0;
        }
        try {
            return trimSafely(stream);
        } catch (ChatDeliveryUnavailableException e) {
            availability.redisUnavailable(e);
            return 0;
        }
    }

    private long trimSafely(ChatJobStream stream) {
        return safeBoundary(stream, Instant.now())
                .map(boundary -> {
                    long trimmed = chatDeliveryQueue.trimBefore(stream, boundary);
                    if (trimmed > 0) {
                        log.info("작업 큐 정리 stream={}, boundary={}, trimmed={}", stream, boundary, trimmed);
                    }
                    return trimmed;
                })
                .orElse(0L);
    }

    /** 이 위치보다 앞선 항목은 지워도 된다. 지울 수 없으면 빈 값 */
    public Optional<String> safeBoundary(ChatJobStream stream, Instant now) {
        Optional<QueueGroupState> state = chatDeliveryQueue.groupState(stream);
        if (state.isEmpty()) {
            return Optional.empty();
        }
        QueueGroupState group = state.get();
        String boundary = group.pendingCount() > 0 && group.minPendingId() != null
                ? ChatStreamEntries.min(group.minPendingId(), group.lastDeliveredId())
                : group.lastDeliveredId();
        boundary = ChatStreamEntries.min(boundary,
                ChatStreamEntries.positionAt(now.minus(properties.trim().retention())));
        if (ChatStreamEntries.compare(boundary, ChatStreamEntries.START) <= 0) {
            return Optional.empty();
        }
        return Optional.of(boundary);
    }
}
