package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;

/**
 * 자기 구독이 오래 끊겨도 작업을 격리하지 않고 기다렸다가, 구독이 돌아오면 한 번씩 발행한다 (2026-10-30 사용자 결정).
 * 재확인 간격 50~200ms, 회수 min-idle 100ms, 발행 시도 상한 3으로 줄여 "상한보다 훨씬 긴 단절"을 짧게 재현한다.
 */
@DirtiesContext
class ChatDeliveryPauseTest extends ChatDeliveryTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final int OUTAGE_ROUNDS = 15;

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @Test
    void 자기_구독이_오래_끊겨도_시도를_늘리지_않고_새_작업_읽기를_멈췄다가_돌아오면_한_번씩_발행한다() {
        UUID first = send("끊기기 직전");
        relayAll(ChatJobStream.DELIVERY);
        fanout.subscribers(0);
        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(checkpoint(first).getLastSubscriberCount()).isZero();

        // 단절 중의 새 작업은 적재되지만 새 작업 읽기를 멈춰 배분하지 않는다
        UUID second = send("끊긴 동안");
        relayAll(ChatJobStream.DELIVERY);
        String lastDelivered = queue.groupState(ChatJobStream.DELIVERY).orElseThrow().lastDeliveredId();
        assertThat(processor.pollNew(ChatJobStream.DELIVERY, WORKER)).isZero();
        assertThat(queue.groupState(ChatJobStream.DELIVERY).orElseThrow().lastDeliveredId()).isEqualTo(lastDelivered);

        // 시도 상한(3)보다 훨씬 긴 단절: 회수 루프가 재확인 시각마다 한 건만 시험 발행한다
        for (int round = 0; round < OUTAGE_ROUNDS; round++) {
            await().pollDelay(Duration.ofMillis(150)).atMost(WAIT).until(() -> true);
            processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10);
            processor.pollNew(ChatJobStream.DELIVERY, WORKER);
        }

        ChatPublishCheckpoint paused = checkpoint(first);
        assertThat(paused.getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(paused.getAttemptCount()).isZero();
        assertThat(paused.getTotalAttemptCount()).isZero();
        assertThat(checkpoint(second).getAttemptCount()).isZero();
        assertThat(deadCount()).isZero();
        // 재확인 발행은 했지만(최소 몇 번) 회수 한 번에 한 건을 넘지 않았다
        assertThat(fanout.publishCalls()).isBetween(3, OUTAGE_ROUNDS + 1);
        assertThat(fanout.messages()).isEmpty();

        fanout.subscribers(1);
        await().atMost(WAIT).until(() -> {
            processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10);
            processor.pollNew(ChatJobStream.DELIVERY, WORKER);
            return checkpoint(first).getStatus() == ChatPublishStatus.PUBLISHED
                    && checkpoint(second).getStatus() == ChatPublishStatus.PUBLISHED;
        });

        assertThat(fanout.messages()).extracting(MessageFanout::deliveryId).containsExactlyInAnyOrder(first, second);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    private long deadCount() {
        return sql.single("""
                        select (select count(*) from chats.chat_publish_checkpoint where status = 'DEAD')
                             + (select count(*) from chats.chat_outbox where status = 'DEAD')
                        """, Long.class);
    }
}
