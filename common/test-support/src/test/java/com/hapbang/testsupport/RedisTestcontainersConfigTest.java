package com.hapbang.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(RedisTestcontainersConfigTest.Config.class)
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

    @Configuration(proxyBeanMethods = false)
    @Import(RedisTestcontainersConfig.class)
    @ImportAutoConfiguration(DataRedisAutoConfiguration.class)
    static class Config {
    }
}
