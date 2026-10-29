package com.hapbang.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Redis 통신이 필요한 테스트에서 @Import 한다. 테스트 JVM에서 한 번 시작하고 종료 후 Testcontainers가 정리한다.
 * 키·채널·소비자 그룹은 테스트마다 고유하게 지정하고 자신이 만든 자원만 정리한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfig {

    private static final int PORT = 6379;

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    static {
        REDIS.start();
    }

    @Bean
    DynamicPropertyRegistrar redisProperties() {
        return registry -> {
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(PORT));
        };
    }
}
