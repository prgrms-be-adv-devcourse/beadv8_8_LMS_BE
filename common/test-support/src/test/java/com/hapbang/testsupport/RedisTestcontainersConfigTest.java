package com.hapbang.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

// 멈춘 Redis에 보낸 명령이 기본 60초를 기다리지 않고 빨리 실패하게 한다
@SpringJUnitConfig(RedisTestcontainersConfigTest.Config.class)
@TestPropertySource(properties = "spring.data.redis.timeout=1s")
class RedisTestcontainersConfigTest {

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void redis설정만으로_값을_저장하고_조회한다() {
        MessagingContainerAssertions.assertRedisRoundTrip(redis);
    }

    @Test
    void stream을_소비하고_ack하면_pending에서_제거된다() {
        MessagingContainerAssertions.assertRedisStreamAcknowledgement(redis);
    }

    @Test
    void redis를_멈춘_동안은_명령이_실패하고_되살리면_다시_된다() {
        String key = "testsupport:paused:" + UUID.randomUUID();

        RedisTestcontainersConfig.withRedisPaused(() -> assertThatThrownBy(() -> redis.opsForValue().set(key, "paused"))
                .isInstanceOf(DataAccessException.class));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            redis.opsForValue().set(key, "resumed");
            assertThat(redis.opsForValue().get(key)).isEqualTo("resumed");
        });
        redis.delete(key);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RedisTestcontainersConfig.class)
    @ImportAutoConfiguration(DataRedisAutoConfiguration.class)
    static class Config {
    }
}
