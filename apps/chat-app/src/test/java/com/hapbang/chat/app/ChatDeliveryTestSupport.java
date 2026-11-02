package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.chat.app.dto.QueueGroupState;
import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatOutboxRepository;
import com.hapbang.chat.out.ChatPublishCheckpointRepository;
import com.hapbang.chat.out.redis.ChatRedisKeys;
import com.hapbang.testsupport.IntegrationTest;

import jakarta.persistence.EntityManager;

/**
 * 전달 파이프라인 통합 테스트 공통. 파이프라인을 켜고(배치 실행은 끈 채) 처리 빈을 직접 부른다.
 * <ul>
 *     <li>Redis는 공용 컨테이너를 쓰고, 테스트 클래스마다 고유한 namespace({@link #uniqueNamespace})를 둔다.
 *     테스트마다 자기 namespace의 Stream 키만 지운다. 전역 초기화(FLUSHALL·FLUSHDB)는 하지 않는다.</li>
 *     <li>재시도 간격은 1ms로 줄인다. backoff 대기는 체크포인트의 다음 시도 시각을 직접 미래로 바꿔 검증한다.</li>
 * </ul>
 */
@IntegrationTest
@Import(ChatDeliveryTestConfig.class)
@TestPropertySource(properties = {
        "hapbang.chat.delivery.pipeline.enabled=true",
        "hapbang.chat.delivery.instance-id=test",
        "hapbang.chat.delivery.relay.max-attempts=2",
        "hapbang.chat.delivery.relay.backoff.initial=1ms",
        "hapbang.chat.delivery.relay.backoff.max=1ms",
        "hapbang.chat.delivery.relay.backoff.jitter=0",
        "hapbang.chat.delivery.consumer.block=50ms",
        "hapbang.chat.delivery.consumer.max-attempts=3",
        "hapbang.chat.delivery.consumer.backoff.initial=1ms",
        "hapbang.chat.delivery.consumer.backoff.max=1ms",
        "hapbang.chat.delivery.consumer.backoff.jitter=0",
        "hapbang.chat.delivery.reclaim.min-idle=100ms",
        "hapbang.chat.delivery.reclaim.count=2",
        "hapbang.chat.delivery.reconstruct.cooldown=1h",
        "hapbang.chat.delivery.reconstruct.max-requeues=2",
        "hapbang.chat.delivery.trim.retention=0s",
        "hapbang.chat.delivery.pause.probe-initial=50ms",
        "hapbang.chat.delivery.pause.probe-max=200ms"
})
abstract class ChatDeliveryTestSupport {

    static final String WORKER = "test:delivery:1";
    static final String RECLAIMER = "test:delivery:reclaim";

    @Autowired
    ChatRoomUseCase chatRoomUseCase;

    @Autowired
    ChatMessageUseCase chatMessageUseCase;

    @Autowired
    ChatUserUseCase chatUserUseCase;

    @Autowired
    ChatOutboxRelay relay;

    @Autowired
    ChatStreamJobProcessor processor;

    @Autowired
    ChatStreamReconstructor reconstructor;

    @Autowired
    ChatDeliveryQueue queue;

    @Autowired
    InMemoryRealtimeChatFanout fanout;

    @Autowired
    ChatDeliveryAvailability availability;

    @Autowired
    ChatOutboxRepository chatOutboxRepository;

    @Autowired
    ChatPublishCheckpointRepository checkpointRepository;

    @Autowired
    ChatRedisKeys keys;

    @Autowired
    StringRedisTemplate redis;

    /** 엔티티로 만들 수 없는 상태(지난 시각 등)를 만들 때 쓰는 native SQL */
    ChatNativeSql sql;

    Long chatRoomId;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    /** 테스트 클래스마다 고유한 Redis namespace. 각 클래스의 {@code @DynamicPropertySource}에서 부른다 */
    static void uniqueNamespace(DynamicPropertyRegistry registry) {
        String namespace = "test-" + UUID.randomUUID();
        registry.add("hapbang.chat.redis.namespace", () -> namespace);
    }

    @BeforeEach
    void setUpDelivery() {
        redis.delete(List.of(keys.deliveryStream(), keys.controlStream()));
        fanout.reset();
        availability.reset();
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        queue.ensureGroup(ChatJobStream.DELIVERY);
        queue.ensureGroup(ChatJobStream.CONTROL);
    }

    UUID send(String content) {
        Long messageId = chatMessageUseCase.send(chatRoomId, HOST_ID, content, null).message().messageId();
        return deliveryIdOf(messageId);
    }

    UUID deliveryIdOf(Long messageId) {
        return chatOutboxRepository.findByMessageId(messageId).orElseThrow().getDeliveryId();
    }

    ChatOutbox outbox(UUID deliveryId) {
        return chatOutboxRepository.findByDeliveryId(deliveryId).orElseThrow();
    }

    ChatPublishCheckpoint checkpoint(UUID deliveryId) {
        return checkpointRepository.findByConsumerGroupAndJobId(ChatJobStream.DELIVERY.consumerGroup(), deliveryId)
                .orElseThrow();
    }

    long pendingCount(ChatJobStream stream) {
        return queue.groupState(stream).map(QueueGroupState::pendingCount).orElse(0L);
    }

    long streamLength(ChatJobStream stream) {
        Long size = redis.opsForStream().size(stream == ChatJobStream.DELIVERY
                ? keys.deliveryStream() : keys.controlStream());
        return size == null ? 0 : size;
    }

    /** 대기 중인 outbox를 모두 적재한다 */
    void relayAll(ChatJobStream stream) {
        while (relay.relayBatch(stream) > 0) {
            // 다음 배치
        }
    }

    /** 새 작업을 더 없을 때까지 읽어 처리한다 */
    void pollAll(ChatJobStream stream, String consumer) {
        while (processor.pollNew(stream, consumer) > 0) {
            // 다음 배치
        }
    }

    /**
     * backoff 대기를 건너뛰고 항목을 처리한다. 연달아 실패를 쌓는 테스트가 1ms backoff 안에 다시 처리해 DEFERRED가
     * 되지 않도록, 처리 전에 다음 시도 시각을 비운다.
     */
    ChatStreamJobProcessor.Outcome handleNow(QueuedJob job, UUID jobId) {
        clearBackoff(jobId);
        return processor.handle(ChatJobStream.DELIVERY, job);
    }

    /** 체크포인트의 다음 시도 시각을 지금으로 당긴다(backoff 대기를 건너뛴다) */
    void clearBackoff(UUID jobId) {
        sql.update("update chats.chat_publish_checkpoint set next_attempt_at = null where job_id = :jobId",
                Map.of("jobId", jobId));
    }
}
