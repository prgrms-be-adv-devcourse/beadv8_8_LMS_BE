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

    /**
     * Redis를 멈춘 채로 {@code action}을 실행하고, 끝나면 성공·실패와 관계없이 되살린다 (#770).
     * <p>
     * {@link KafkaTestcontainersConfig#withBrokerPaused}와 같이 컨테이너를 {@code docker pause}로 멈춰 응답하지 않는 Redis를 흉내 낸다.
     * stop/start는 매핑 포트가 바뀌어 client가 다시 붙지 못하므로 쓰지 않는다. 컨테이너는 같은 JVM의 모든 테스트가 같이 쓰므로 멈춘 채로 두면 안 된다.
     * 멈춘 동안의 명령은 연결이 끊기지 않고 응답을 기다리므로, 빨리 실패하게 하려면 테스트에서 {@code spring.data.redis.timeout}을 줄인다
     * (기본 60초).
     */
    public static void withRedisPaused(Runnable action) {
        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            action.run();
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Bean
    DynamicPropertyRegistrar redisProperties() {
        return registry -> {
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(PORT));
        };
    }
}
