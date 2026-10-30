package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.QueueGroupState;
import com.hapbang.chat.app.dto.QueuedJob;
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
 * 실제 Redis 장애 시험 (테스트 기준 "Stream 유실·보존"의 영속화·noeviction·OOM, 설계 9장 "Stream 보존·재구성").
 * <p>
 * 공용 Redis 컨테이너를 멈추거나 설정을 바꾸지 않도록 이 클래스만 쓰는 별도 Redis 컨테이너를 띄운다.
 * 운영 후보 설정을 명시한다: AOF({@code appendonly yes}, {@code appendfsync always}), {@code maxmemory-policy noeviction}.
 * 재시작 뒤 같은 주소로 다시 붙도록 호스트 포트를 고정한다. 프로세스 강제 종료는 {@code docker kill}(SIGKILL)이다.
 * 운영 Redis의 실제 배포 설정·fsync 정책·메모리 상한 수치는 Q12 확정 대상이라 여기서 확정하지 않는다.
 */
@IntegrationTest
@DirtiesContext
@Import(ChatDeliveryRedisFailureTest.FanoutConfig.class)
@TestPropertySource(properties = {
        "hapbang.chat.delivery.pipeline.enabled=true",
        "hapbang.chat.delivery.instance-id=test",
        "hapbang.chat.delivery.relay.max-attempts=5",
        "hapbang.chat.delivery.relay.backoff.initial=1ms",
        "hapbang.chat.delivery.relay.backoff.max=1ms",
        "hapbang.chat.delivery.relay.backoff.jitter=0",
        "hapbang.chat.delivery.consumer.block=50ms",
        "hapbang.chat.delivery.consumer.max-attempts=3",
        "hapbang.chat.delivery.consumer.backoff.initial=1ms",
        "hapbang.chat.delivery.consumer.backoff.max=1ms",
        "hapbang.chat.delivery.consumer.backoff.jitter=0",
        "hapbang.chat.delivery.reclaim.min-idle=100ms",
        "hapbang.chat.delivery.reconstruct.cooldown=1h",
        // 장애 중 명령이 연결 복구를 기다리며 오래 멈추지 않게 한다(운영 기본값은 Lettuce 60초)
        "spring.data.redis.timeout=2s",
        // Redis 연결 불가 동안 멈췄다가 재확인하는 간격을 줄인다(2026-10-30)
        "hapbang.chat.delivery.pause.probe-initial=50ms",
        "hapbang.chat.delivery.pause.probe-max=200ms"
})
class ChatDeliveryRedisFailureTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
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
    private ChatDeliveryQueue queue;

    @Autowired
    private InMemoryRealtimeChatFanout fanout;

    @Autowired
    private ChatDeliveryAvailability availability;

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

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        if (!Boolean.TRUE.equals(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec()
                .getState().getRunning())) {
            startRedis();
        }
        awaitRedisReady();
        setConfig("maxmemory", "0");
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        fanout.reset();
        availability.reset();
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        queue.ensureGroup(ChatJobStream.DELIVERY);
        queue.ensureGroup(ChatJobStream.CONTROL);
    }

    @Test
    void AOF_영속화면_강제_종료_뒤에도_Stream_그룹_미확인_목록이_남고_장애_중_작업은_outbox에서_다시_적재된다() {
        UUID handled = send("처리 완료");
        UUID pending = send("읽고 멈춤");
        UUID undelivered = send("미배분");
        relayAll();
        processor.handle(ChatJobStream.DELIVERY,
                queue.readNew(ChatJobStream.DELIVERY, WORKER, 1, Duration.ofMillis(50)).getFirst());
        QueuedJob stalled = queue.readNew(ChatJobStream.DELIVERY, "test-crashed:delivery:1", 1,
                Duration.ofMillis(50)).getFirst();
        assertThat(entryIdOf(pending)).isEqualTo(stalled.entryId());

        killRedis();
        // Redis가 죽은 동안의 전송: 메시지·outbox는 DB에 커밋되고, 적재 실패는 outbox에 남는다.
        // 연결 불가는 인프라 장애라 적재 시도 횟수에 넣지 않는다(2026-10-30 사용자 결정)
        UUID duringOutage = send("장애 중");
        relay.relayBatch(ChatJobStream.DELIVERY);
        assertThat(outbox(duringOutage).getStatus()).isEqualTo(ChatOutboxStatus.PENDING);
        assertThat(outbox(duringOutage).getAttemptCount()).isZero();
        assertThat(outbox(duringOutage).getLastError()).isNotBlank();

        startRedis();
        awaitRedisReady();

        // AOF에서 본문·그룹 위치·미확인 목록을 되살렸다
        assertThat(streamLength()).isEqualTo(3);
        QueueGroupState state = queue.groupState(ChatJobStream.DELIVERY).orElseThrow();
        assertThat(state.pendingCount()).isEqualTo(1);
        assertThat(state.minPendingId()).isEqualTo(stalled.entryId());
        assertThat(state.lastDeliveredId()).isEqualTo(stalled.entryId());

        waitUntilDue(duringOutage);
        relayAll();
        await().atMost(WAIT).ignoreExceptions().until(() -> {
            relay.relayBatch(ChatJobStream.DELIVERY);
            processor.pollNew(ChatJobStream.DELIVERY, WORKER);
            processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10);
            return List.of(handled, pending, undelivered, duringOutage).stream()
                    .allMatch(id -> checkpointStatus(id) == ChatPublishStatus.PUBLISHED);
        });
        assertThat(fanout.messages()).extracting(MessageFanout::deliveryId)
                .containsExactlyInAnyOrder(handled, pending, undelivered, duringOutage);
        assertThat(queue.groupState(ChatJobStream.DELIVERY).orElseThrow().pendingCount()).isZero();
    }

    @Test
    void 데이터를_잃고_재시작하면_읽기에서_그룹_유실을_발견해_미완료_작업만_DB에서_다시_적재한다() {
        UUID published = send("발행 완료");
        relayAll();
        pollAll();
        UUID pending = send("읽고 멈춤");
        UUID undelivered = send("미배분");
        relayAll();
        queue.readNew(ChatJobStream.DELIVERY, "test-crashed:delivery:1", 1, Duration.ofMillis(50));
        // 첫 정기 대조를 미리 돌려, 다음 대조는 유실을 발견한 요청으로만 바로 실행되게 한다
        reconstructor.reconstructIfDue(ChatJobStream.DELIVERY);

        // 영속화 없는 재시작과 같은 결과: 데이터가 비고 프로세스가 죽었다 살아난다
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        killRedis();
        startRedis();
        awaitRedisReady();
        assertThat(streamLength()).isZero();

        await().atMost(WAIT).ignoreExceptions().until(() -> {
            processor.pollNew(ChatJobStream.DELIVERY, WORKER);
            return queue.groupState(ChatJobStream.DELIVERY).isPresent();
        });
        assertThat(reconstructor.reconstructIfDue(ChatJobStream.DELIVERY)).isEqualTo(2);
        pollAll();

        assertThat(checkpointStatus(pending)).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(checkpointStatus(undelivered)).isEqualTo(ChatPublishStatus.PUBLISHED);
        // 이미 발행한 작업은 Redis 유실만으로 다시 발행하지 않는다
        assertThat(fanout.messages()).extracting(MessageFanout::deliveryId)
                .containsExactlyInAnyOrder(published, pending, undelivered);
        assertThat(outbox(published).getRequeueCount()).isZero();
    }

    @Test
    void 메모리_상한에서_noeviction이면_적재만_멈춰_outbox에_남고_시도로_세지_않으며_기존_작업은_지워지지_않고_상한이_풀리면_적재한다() {
        UUID before = send("상한 전");
        relayAll();
        assertThat(getConfig("maxmemory-policy")).isEqualTo("noeviction");

        setConfig("maxmemory", "1");
        try {
            // 이 Redis는 쓰기 명령을 OOM으로 거절하는 상태다
            assertThatThrownBy(() -> redis.opsForStream().add(keys.deliveryStream() + ":probe", Map.of("k", "v")))
                    .rootCause().hasMessageContaining("OOM");
            UUID blocked = send("상한 중");

            relay.relayBatch(ChatJobStream.DELIVERY);

            // 메시지 저장은 성공했고, 메모리 상한(OOM)은 backpressure라 적재를 멈추고 outbox에 남긴다(설계 4.5).
            // 적재 시도로 세지 않으므로 시도 상한(5)을 넘게 재확인해도 격리하지 않는다(2026-10-30 결정)
            assertThat(outbox(blocked).getStatus()).isEqualTo(ChatOutboxStatus.PENDING);
            assertThat(outbox(blocked).getAttemptCount()).isZero();
            assertThat(outbox(blocked).getLastError()).isNotBlank();
            for (int round = 0; round < 8; round++) {
                waitUntilDue(blocked);
                // 재확인 간격 상한(200ms)을 넘겨 매번 한 건의 XADD로 재확인하게 한다
                await().pollDelay(Duration.ofMillis(220)).atMost(WAIT).until(() -> true);
                relay.relayBatch(ChatJobStream.DELIVERY);
            }
            assertThat(outbox(blocked).getStatus()).isEqualTo(ChatOutboxStatus.PENDING);
            assertThat(outbox(blocked).getAttemptCount()).isZero();
            assertThat(outbox(blocked).getTotalAttemptCount()).isZero();
            assertThat(checkpointStatus(blocked)).isEqualTo(ChatPublishStatus.PENDING);
            // 이미 적재한 작업은 eviction으로 사라지지 않는다
            assertThat(streamLength()).isEqualTo(1);
            assertThat(outbox(before).getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);

            // 적체된 작업 소비(XREADGROUP·XACK)는 상한 중에도 진행된다
            pollAll();
            assertThat(checkpointStatus(before)).isEqualTo(ChatPublishStatus.PUBLISHED);
            assertThat(queue.groupState(ChatJobStream.DELIVERY).orElseThrow().pendingCount()).isZero();

            setConfig("maxmemory", "0");
            // 재확인 시각에 한 건의 XADD가 성공하면 적재를 이어 간다
            await().atMost(WAIT).until(() -> {
                waitUntilDue(blocked);
                relay.relayBatch(ChatJobStream.DELIVERY);
                pollAll();
                return checkpointStatus(blocked) == ChatPublishStatus.PUBLISHED;
            });

            assertThat(outbox(blocked).getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);
            assertThat(outbox(blocked).getAttemptCount()).isEqualTo(1);
            assertThat(checkpointStatus(blocked)).isEqualTo(ChatPublishStatus.PUBLISHED);
            assertThat(fanout.messages()).extracting(MessageFanout::deliveryId).containsExactly(before, blocked);
        } finally {
            setConfig("maxmemory", "0");
        }
    }

    private UUID send(String content) {
        Long messageId = chatMessageUseCase.send(chatRoomId, HOST_ID, content, null).message().messageId();
        return chatOutboxRepository.findByMessageId(messageId).orElseThrow().getDeliveryId();
    }

    private ChatOutbox outbox(UUID deliveryId) {
        return chatOutboxRepository.findByDeliveryId(deliveryId).orElseThrow();
    }

    private String entryIdOf(UUID deliveryId) {
        return outbox(deliveryId).getStreamEntryId();
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

    private void pollAll() {
        while (processor.pollNew(ChatJobStream.DELIVERY, WORKER) > 0) {
            // 다음 배치
        }
    }

    private long streamLength() {
        Long size = redis.opsForStream().size(keys.deliveryStream());
        return size == null ? 0 : size;
    }

    private void waitUntilDue(UUID deliveryId) {
        jdbcClient.sql("update chats.chat_outbox set next_attempt_at = :past where delivery_id = :id")
                .param("past", ChatDeliveryClock.now().minusSeconds(1))
                .param("id", deliveryId)
                .update();
    }

    private void setConfig(String name, String value) {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().setConfig(name, value);
            return null;
        });
    }

    private String getConfig(String name) {
        Properties config = redis.execute((RedisCallback<Properties>) connection ->
                connection.serverCommands().getConfig(name));
        return config == null ? null : config.getProperty(name);
    }

    private void awaitRedisReady() {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).ignoreExceptions().until(() -> {
            redis.opsForStream().size(keys.deliveryStream());
            return true;
        });
    }

    private static void killRedis() {
        REDIS.getDockerClient().killContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void startRedis() {
        REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
    }

}
