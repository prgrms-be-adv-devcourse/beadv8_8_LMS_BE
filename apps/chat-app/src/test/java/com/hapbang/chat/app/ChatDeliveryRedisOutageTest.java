package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.domain.ChatOutboxStatus;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.fixture.FixedPortRedis;
import com.hapbang.chat.out.ChatOutboxRepository;
import com.hapbang.chat.out.ChatPublishCheckpointRepository;
import com.hapbang.chat.out.redis.ChatRedisKeys;
import com.hapbang.testsupport.IntegrationTest;

/**
 * Redis가 오래 멈춰도 연결 불가를 작업의 시도로 세지 않아 격리(DEAD)하지 않고, 돌아오면 모두 한 번씩 발행한다
 * (2026-10-30 사용자 결정). 이 클래스만 쓰는 Redis 컨테이너(AOF, 고정 호스트 포트)를 강제 종료·재시작한다.
 * 운영 기준 10분 단절을, 적재·발행 시도 상한(2)과 재적재 상한(1)보다 훨씬 많은 relay·읽기·회수·재구성 호출로 줄여 재현한다.
 */
@IntegrationTest
@DirtiesContext
@Import(ChatDeliveryRedisOutageTest.FanoutConfig.class)
@TestPropertySource(properties = {
        "hapbang.chat.delivery.pipeline.enabled=true",
        "hapbang.chat.delivery.instance-id=test",
        "hapbang.chat.delivery.relay.max-attempts=2",
        "hapbang.chat.delivery.relay.backoff.initial=1ms",
        "hapbang.chat.delivery.relay.backoff.max=1ms",
        "hapbang.chat.delivery.relay.backoff.jitter=0",
        "hapbang.chat.delivery.consumer.block=50ms",
        "hapbang.chat.delivery.consumer.max-attempts=2",
        "hapbang.chat.delivery.consumer.backoff.initial=1ms",
        "hapbang.chat.delivery.consumer.backoff.max=1ms",
        "hapbang.chat.delivery.consumer.backoff.jitter=0",
        "hapbang.chat.delivery.reclaim.min-idle=100ms",
        "hapbang.chat.delivery.reconstruct.cooldown=1ms",
        "hapbang.chat.delivery.reconstruct.max-requeues=1",
        "hapbang.chat.delivery.pause.probe-initial=50ms",
        "hapbang.chat.delivery.pause.probe-max=200ms",
        "spring.data.redis.timeout=500ms"
})
class ChatDeliveryRedisOutageTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final int OUTAGE_ROUNDS = 12;
    private static final String WORKER = "test:delivery:1";
    private static final String RECLAIMER = "test:delivery:reclaim";

    /** 장애 시험 전용 Redis. 죽였다 다시 띄워도 같은 주소로 붙도록 호스트 포트를 고정한다(포트 충돌이면 새 포트로 다시 띄운다) */
    private static final FixedPortRedis DEDICATED = FixedPortRedis.start("redis-server", "--appendonly", "yes",
            "--appendfsync", "always", "--maxmemory-policy", "noeviction");
    private static final GenericContainer<?> REDIS = DEDICATED.container();
    private static final int HOST_PORT = DEDICATED.hostPort();

    @TestConfiguration(proxyBeanMethods = false)
    static class FanoutConfig {

        @Bean
        InMemoryRealtimeChatFanout inMemoryRealtimeChatFanout() {
            return new InMemoryRealtimeChatFanout();
        }
    }

    @DynamicPropertySource
    static void dedicatedRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> HOST_PORT);
        registry.add("hapbang.chat.redis.namespace", () -> "test-" + UUID.randomUUID());
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatOutboxRelay relay;

    @Autowired
    private ChatStreamJobProcessor processor;

    @Autowired
    private ChatStreamReconstructor reconstructor;

    @Autowired
    private ChatStreamTrimmer trimmer;

    @Autowired
    private ChatDeliveryQueue queue;

    @Autowired
    private ChatDeliveryAvailability availability;

    @Autowired
    private InMemoryRealtimeChatFanout fanout;

    @Autowired
    private ChatOutboxRepository chatOutboxRepository;

    @Autowired
    private ChatPublishCheckpointRepository checkpointRepository;

    @Autowired
    private ChatRedisKeys keys;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void Redis가_시도_상한보다_오래_멈춰도_격리하지_않고_시도를_늘리지_않으며_돌아오면_모두_한_번씩_발행한다() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        queue.ensureGroup(ChatJobStream.DELIVERY);
        UUID pending = send(chatRoomId, "읽고 멈춤");
        UUID undelivered = send(chatRoomId, "미배분");
        relayAll();
        queue.readNew(ChatJobStream.DELIVERY, "test-crashed:delivery:1", 1, Duration.ofMillis(50));
        // 장애 전에 성공한 적재 시도(작업마다 1회)
        long attemptsBeforeOutage = attemptSum();

        killRedis();
        UUID duringOutage = send(chatRoomId, "장애 중");
        for (int round = 0; round < OUTAGE_ROUNDS; round++) {
            dueNow();
            relay.relayBatch(ChatJobStream.DELIVERY);
            processor.pollNew(ChatJobStream.DELIVERY, WORKER);
            processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10);
            reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO);
            trimmer.trim(ChatJobStream.DELIVERY);
            // 재확인 간격 상한(200ms)을 넘겨 매번 재확인이 일어나게 한다
            await().pollDelay(Duration.ofMillis(220)).atMost(WAIT).until(() -> true);
        }

        assertThat(availability.redisAvailable()).isFalse();
        assertThat(deadCount()).isZero();
        assertThat(attemptSum()).isEqualTo(attemptsBeforeOutage);
        assertThat(outbox(duringOutage).getStatus()).isEqualTo(ChatOutboxStatus.PENDING);
        assertThat(List.of(pending, undelivered)).allSatisfy(id -> {
            assertThat(outbox(id).getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);
            assertThat(outbox(id).getRequeueCount()).isZero();
        });
        assertThat(fanout.publishCalls()).isZero();

        startRedis();
        await().atMost(WAIT).ignoreExceptions().until(() -> {
            relay.relayBatch(ChatJobStream.DELIVERY);
            processor.pollNew(ChatJobStream.DELIVERY, WORKER);
            processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10);
            reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO);
            return List.of(pending, undelivered, duringOutage).stream()
                    .allMatch(id -> checkpointStatus(id) == ChatPublishStatus.PUBLISHED);
        });

        assertThat(fanout.messages()).extracting(MessageFanout::deliveryId)
                .containsExactlyInAnyOrder(pending, undelivered, duringOutage);
        assertThat(deadCount()).isZero();
        assertThat(outbox(duringOutage).getAttemptCount()).isEqualTo(1);
    }

    private UUID send(Long chatRoomId, String content) {
        Long messageId = chatMessageUseCase.send(chatRoomId, HOST_ID, content, null).message().messageId();
        return chatOutboxRepository.findByMessageId(messageId).orElseThrow().getDeliveryId();
    }

    private ChatOutbox outbox(UUID deliveryId) {
        return chatOutboxRepository.findByDeliveryId(deliveryId).orElseThrow();
    }

    private ChatPublishStatus checkpointStatus(UUID deliveryId) {
        return checkpointRepository.findByConsumerGroupAndJobId(ChatJobStream.DELIVERY.consumerGroup(), deliveryId)
                .orElseThrow().getStatus();
    }

    private void relayAll() {
        while (relay.relayBatch(ChatJobStream.DELIVERY) > 0) {
            // 다음 배치
        }
    }

    private void dueNow() {
        jdbcClient.sql("update chats.chat_outbox set next_attempt_at = :past where status = 'PENDING'")
                .param("past", ChatDeliveryClock.now().minusSeconds(1))
                .update();
    }

    private long deadCount() {
        return jdbcClient.sql("""
                        select (select count(*) from chats.chat_publish_checkpoint where status = 'DEAD')
                             + (select count(*) from chats.chat_outbox where status = 'DEAD')
                        """)
                .query(Long.class)
                .single();
    }

    private long attemptSum() {
        return jdbcClient.sql("""
                        select (select coalesce(sum(attempt_count + total_attempt_count), 0)
                                from chats.chat_publish_checkpoint)
                             + (select coalesce(sum(attempt_count + total_attempt_count + requeue_count), 0)
                                from chats.chat_outbox)
                        """)
                .query(Long.class)
                .single();
    }

    private static void killRedis() {
        REDIS.getDockerClient().killContainerCmd(REDIS.getContainerId()).exec();
    }

    private void startRedis() {
        REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).ignoreExceptions().until(() -> {
            redis.opsForStream().size(keys.deliveryStream());
            return true;
        });
    }

}
