package com.hapbang.chat.out.redis;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.script.ScriptingException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Lua 스크립트 파일 읽기와 KEYS·ARGV 실행을 실제 Redis로 확인한다: 파일 스크립트를 값만 바꿔 실행, 없는 파일은 만들 때 바로 실패.
 * 다른 테스트와 나누지 않도록 전용 컨테이너를 쓴다.
 */
class RedisLuaSupportTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private String namespace;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(),
                REDIS.getMappedPort(6379)), LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
                .build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        namespace = "lua-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void 파일의_스크립트를_KEYS와_ARGV로_실행한다() {
        RedisScript<Long> connect = RedisLuaSupport.longLuaScript("redis/scripts/presence-connect.lua");
        RedisScript<Long> disconnect = RedisLuaSupport.longLuaScript("redis/scripts/presence-disconnect.lua");
        List<String> keys = List.of(namespace + ":user:7", namespace + ":online");
        long now = System.currentTimeMillis();

        assertThat(RedisLuaSupport.runForLong(redis, connect, keys, "app:s-1", now + 90_000, 7L, 90_000L, now))
                .isEqualTo(1L);
        RedisLuaSupport.runForLong(redis, connect, keys, "app:s-2", now + 90_000, 7L, 90_000L, now);
        assertThat(redis.opsForZSet().score(keys.get(0), "app:s-1")).isEqualTo((double) (now + 90_000));
        assertThat(redis.opsForZSet().score(keys.get(1), "7")).isEqualTo((double) (now + 90_000));

        assertThat(RedisLuaSupport.runForLong(redis, disconnect, keys, "app:s-1", now, 7L)).isEqualTo(1L);
        assertThat(redis.opsForZSet().score(keys.get(1), "7")).isNotNull();
        assertThat(RedisLuaSupport.runForLong(redis, disconnect, keys, "app:s-2", now, 7L)).isZero();
        assertThat(redis.opsForZSet().score(keys.get(1), "7")).isNull();
    }

    @Test
    void 없는_스크립트_파일은_만들_때_바로_실패한다() {
        assertThatThrownBy(() -> RedisLuaSupport.longLuaScript("redis/scripts/missing.lua"))
                .isInstanceOf(ScriptingException.class);
    }
}
