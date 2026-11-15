package com.hapbang.auxiliary.notification.out.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.BackfillRun;
import com.hapbang.auxiliary.notification.app.BackfillRunStore;

import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 Backfill 실행 상태를 Redis에 둔다(ND-092). 키는 {@code <접두어>:backfill:run:<실행 ID>}, 값은 실행 상태 JSON, TTL은 7일이다.
 * 접두어는 실시간 전달과 같은 {@code hapbang.notification.realtime.redis-prefix}(기본 {@code notification})이고, 실시간 전달 스위치와
 * 상관없이 쓴다. 쓰기 실패는 예외로 알린다(부른 쪽이 시작을 거절하거나 갱신만 건너뛴다).
 */
@Component
public class RedisBackfillRunStore implements BackfillRunStore {

    static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final String prefix;

    public RedisBackfillRunStore(StringRedisTemplate redis, JsonMapper jsonMapper,
            @Value("${hapbang.notification.realtime.redis-prefix:notification}") String prefix) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.prefix = prefix;
    }

    @Override
    public void save(BackfillRun run) {
        redis.opsForValue().set(key(run.runId()), jsonMapper.writeValueAsString(run), TTL);
    }

    @Override
    public Optional<BackfillRun> find(String runId) {
        String json = redis.opsForValue().get(key(runId));
        return json == null ? Optional.empty() : Optional.of(jsonMapper.readValue(json, BackfillRun.class));
    }

    private String key(String runId) {
        return prefix + ":backfill:run:" + runId;
    }
}
