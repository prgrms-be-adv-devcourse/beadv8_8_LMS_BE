package com.hapbang.chat.out.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XTrimOptions;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.hapbang.chat.app.ChatDeliveryBackpressureException;
import com.hapbang.chat.app.ChatDeliveryQueue;
import com.hapbang.chat.app.ChatDeliveryUnavailableException;
import com.hapbang.chat.app.ChatJobStream;
import com.hapbang.chat.app.ChatQueueGroupMissingException;
import com.hapbang.chat.app.ChatStreamEntries;
import com.hapbang.chat.app.dto.QueueGroupState;
import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.app.dto.ReclaimedJobs;
import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import lombok.extern.slf4j.Slf4j;

/**
 * 작업 큐의 Redis Streams 구현. 이름은 {@link ChatRedisKeys}를 따른다.
 * <ul>
 *     <li>필수 그룹은 {@code XGROUP CREATE <key> <group> 0-0 MKSTREAM}으로 만든다. {@code BUSYGROUP}은 이미 있는 정상 상태다.
 *     재시작 때 {@code $}로 만들어 보존된 작업을 건너뛰지 않는다.</li>
 *     <li>새 작업은 {@code XREADGROUP ... COUNT n BLOCK ms STREAMS <key> >}로 읽고 {@code NOACK}를 쓰지 않는다.
 *     블로킹 읽기는 consumer마다 묶어 둔 전용 연결로 한다(공유 연결을 막지 않는다).</li>
 *     <li>회수는 {@code XAUTOCLAIM}이다. Spring Data Redis에 명령이 없어 Lettuce 연결로 직접 보낸다. 응답의 세 번째 값
 *     (본문이 사라진 항목)을 함께 돌려준다.</li>
 *     <li>정리는 {@code XTRIM MINID =}만 쓴다. 길이(MAXLEN)로 자르지 않는다.</li>
 *     <li>연결 불가(분류는 {@link RedisFailures})는 {@link ChatDeliveryUnavailableException}, 메모리 상한(OOM)은
 *     {@link ChatDeliveryBackpressureException}으로 바꿔 던진다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnChatDeliveryPipeline
public class RedisChatDeliveryQueue implements ChatDeliveryQueue, DisposableBean {

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final ChatRedisKeys keys;
    /** 연결·쓰기 수락 재확인 키(전달 Stream 키 뒤에 붙인다)와 TTL */
    private static final String PROBE_SUFFIX = ":probe";
    private static final Duration PROBE_TTL = Duration.ofSeconds(10);

    private final Map<String, RedisConnection> readers = new ConcurrentHashMap<>();

    public RedisChatDeliveryQueue(StringRedisTemplate redisTemplate, RedisConnectionFactory connectionFactory,
            ChatRedisKeys keys) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.keys = keys;
    }

    @Override
    public void ensureGroup(ChatJobStream stream) {
        byte[] key = bytes(keyOf(stream));
        call("XGROUP CREATE", () -> redisTemplate.execute((RedisCallback<Void>) connection -> {
            try {
                connection.streamCommands().xGroupCreate(key, stream.consumerGroup(),
                        ReadOffset.from(ChatStreamEntries.START), true);
                log.info("consumer group 생성 stream={}, group={}", keyOf(stream), stream.consumerGroup());
            } catch (DataAccessException e) {
                if (!RedisFailures.hasMessage(e, "BUSYGROUP")) {
                    throw e;
                }
            }
            return null;
        }));
    }

    @Override
    public String enqueue(ChatJobStream stream, Map<String, String> fields) {
        RecordId id = call("XADD",
                () -> redisTemplate.opsForStream().add(StreamRecords.newRecord().in(keyOf(stream)).ofMap(fields)));
        Assert.state(id != null, "XADD 응답이 없습니다.");
        return id.getValue();
    }

    @Override
    public List<QueuedJob> readNew(ChatJobStream stream, String consumer, int count, Duration block) {
        Assert.isTrue(block != null && !block.isZero() && !block.isNegative(), "block은 0보다 커야 합니다.");
        try {
            // 연결을 얻는 단계(기동 때 Redis가 내려가 있는 경우 등)의 실패도 같은 분류를 거친다
            RedisConnection connection = readers.computeIfAbsent(consumer, name -> connectionFactory.getConnection());
            List<ByteRecord> records = connection.streamCommands().xReadGroup(
                    Consumer.from(stream.consumerGroup(), consumer),
                    StreamReadOptions.empty().count(count).block(block),
                    StreamOffset.create(bytes(keyOf(stream)), ReadOffset.lastConsumed()));
            if (records == null) {
                return List.of();
            }
            return records.stream().map(RedisChatDeliveryQueue::toJob).toList();
        } catch (RuntimeException e) {
            releaseConsumer(consumer);
            if (RedisFailures.isConnectivityFailure(e)) {
                throw unavailable("XREADGROUP", e);
            }
            if (RedisFailures.hasMessage(e, "NOGROUP")) {
                throw new ChatQueueGroupMissingException(stream, e);
            }
            throw e;
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ReclaimedJobs reclaim(ChatJobStream stream, String consumer, Duration minIdle, String cursor, int count) {
        Object raw;
        try (RedisConnection connection = connectionFactory.getConnection()) {
            if (!(connection instanceof LettuceConnection lettuce)) {
                throw new IllegalStateException("XAUTOCLAIM은 Lettuce 연결이 필요합니다: " + connection.getClass());
            }
            raw = lettuce.execute("XAUTOCLAIM", new NestedMultiOutput<>(ByteArrayCodec.INSTANCE),
                    bytes(keyOf(stream)), bytes(stream.consumerGroup()), bytes(consumer),
                    bytes(Long.toString(minIdle.toMillis())), bytes(cursor), bytes("COUNT"),
                    bytes(Integer.toString(count)));
        } catch (RuntimeException e) {
            if (RedisFailures.isConnectivityFailure(e)) {
                throw unavailable("XAUTOCLAIM", e);
            }
            if (RedisFailures.hasMessage(e, "NOGROUP")) {
                throw new ChatQueueGroupMissingException(stream, e);
            }
            throw e;
        }
        return toReclaimed(raw);
    }

    @Override
    public void ack(ChatJobStream stream, String entryId) {
        call("XACK", () -> redisTemplate.opsForStream().acknowledge(keyOf(stream), stream.consumerGroup(), entryId));
    }

    @Override
    public boolean isOutstanding(ChatJobStream stream, String entryId) {
        return call("XRANGE·XPENDING", () -> outstanding(stream, entryId));
    }

    private boolean outstanding(ChatJobStream stream, String entryId) {
        String key = keyOf(stream);
        List<MapRecord<String, Object, Object>> body = redisTemplate.opsForStream().range(key,
                Range.closed(entryId, entryId));
        if (body == null || body.isEmpty()) {
            return false;
        }
        Optional<QueueGroupState> group = readGroupState(stream);
        if (group.isEmpty()) {
            // 그룹은 처음 위치(0-0)부터 다시 만들므로 남아 있는 본문은 배분된다
            return true;
        }
        if (ChatStreamEntries.compare(entryId, group.get().lastDeliveredId()) > 0) {
            return true;
        }
        var pending = redisTemplate.opsForStream().pending(key, stream.consumerGroup(),
                Range.closed(entryId, entryId), 1);
        return pending != null && !pending.isEmpty();
    }

    @Override
    public Optional<QueueGroupState> groupState(ChatJobStream stream) {
        return call("XINFO GROUPS·XPENDING", () -> readGroupState(stream));
    }

    private Optional<QueueGroupState> readGroupState(ChatJobStream stream) {
        String key = keyOf(stream);
        List<XInfoGroup> groups = new ArrayList<>();
        try {
            redisTemplate.opsForStream().groups(key).forEach(groups::add);
        } catch (DataAccessException e) {
            if (RedisFailures.hasMessage(e, "no such key")) {
                return Optional.empty();
            }
            throw e;
        }
        Optional<XInfoGroup> required = groups.stream()
                .filter(group -> stream.consumerGroup().equals(group.groupName()))
                .findFirst();
        if (required.isEmpty()) {
            return Optional.empty();
        }
        long pendingCount = required.get().pendingCount();
        String minPendingId = null;
        if (pendingCount > 0) {
            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(key, stream.consumerGroup());
            minPendingId = summary == null ? null : summary.minMessageId();
        }
        return Optional.of(new QueueGroupState(stream.consumerGroup(), required.get().lastDeliveredId(), pendingCount,
                minPendingId));
    }

    @Override
    public long trimBefore(ChatJobStream stream, String minEntryId) {
        Long trimmed = call("XTRIM", () -> redisTemplate.opsForStream().trim(keyOf(stream),
                XTrimOptions.trim(TrimOptions.minId(RecordId.of(minEntryId)).exact())));
        return trimmed == null ? 0 : trimmed;
    }

    /**
     * 연결과 쓰기 수락을 함께 확인한다: {@code SET <전달 Stream 키>:probe 1 PX 10000}. PING은 READONLY 복제본·LOADING·MASTERDOWN
     * 에서도 성공해 적재·읽기가 계속 실패하는 동안 재개를 반복하게 하므로 쓰지 않는다. 키는 namespace 아래 짧은 TTL 문자열이다.
     */
    @Override
    public void ping() {
        call("SET probe", () -> {
            redisTemplate.opsForValue().set(keys.deliveryStream() + PROBE_SUFFIX, "1", PROBE_TTL);
            return null;
        });
    }

    @Override
    public void releaseConsumer(String consumer) {
        RedisConnection connection = readers.remove(consumer);
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException e) {
                log.debug("consumer 연결 종료 실패 consumer={}", consumer, e);
            }
        }
    }

    @Override
    public void destroy() {
        List.copyOf(readers.keySet()).forEach(this::releaseConsumer);
    }

    private String keyOf(ChatJobStream stream) {
        return switch (stream) {
            case DELIVERY -> keys.deliveryStream();
            case CONTROL -> keys.controlStream();
        };
    }

    private static QueuedJob toJob(ByteRecord record) {
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((field, value) -> fields.put(string(field), string(value)));
        return new QueuedJob(record.getId().getValue(), fields);
    }

    /** XAUTOCLAIM 응답: [다음 cursor, [[id, [field, value, ...]], ...], [본문이 사라진 id, ...]] */
    private static ReclaimedJobs toReclaimed(Object raw) {
        if (!(raw instanceof List<?> reply) || reply.isEmpty()) {
            throw new IllegalStateException("XAUTOCLAIM 응답 형식이 다릅니다: " + raw);
        }
        String nextCursor = string((byte[]) reply.get(0));
        List<QueuedJob> jobs = new ArrayList<>();
        if (reply.size() > 1 && reply.get(1) instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof List<?> parts) || parts.size() < 2 || !(parts.get(1) instanceof List<?> kv)) {
                    continue;
                }
                Map<String, String> fields = new LinkedHashMap<>();
                for (int i = 0; i + 1 < kv.size(); i += 2) {
                    fields.put(string((byte[]) kv.get(i)), string((byte[]) kv.get(i + 1)));
                }
                jobs.add(new QueuedJob(string((byte[]) parts.get(0)), fields));
            }
        }
        List<String> deleted = new ArrayList<>();
        if (reply.size() > 2 && reply.get(2) instanceof List<?> ids) {
            ids.forEach(id -> deleted.add(string((byte[]) id)));
        }
        return new ReclaimedJobs(nextCursor, jobs, deleted);
    }

    /**
     * Redis 연결 불가는 {@link ChatDeliveryUnavailableException}, 쓰기 거절(OOM·MISCONF)은 {@link ChatDeliveryBackpressureException}으로
     * 바꾼다. 그 밖의 명령 오류는 그대로 던진다.
     */
    private static <T> T call(String command, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            if (RedisFailures.isConnectivityFailure(e)) {
                throw unavailable(command, e);
            }
            if (RedisFailures.isWriteRejected(e)) {
                throw new ChatDeliveryBackpressureException("Redis가 쓰기를 거절함(메모리 상한·영속화 오류, " + command + ")",
                        e);
            }
            throw e;
        }
    }

    private static ChatDeliveryUnavailableException unavailable(String command, RuntimeException cause) {
        return new ChatDeliveryUnavailableException("Redis에 연결할 수 없습니다(" + command + ")", cause);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String string(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
