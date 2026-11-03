package com.hapbang.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Kafka 통신이 필요한 테스트에서 @Import 한다. 테스트 JVM에서 한 번 시작하고 종료 후 Testcontainers가 정리한다.
 * 토픽·소비자 그룹·직렬화 설정은 사용하는 테스트가 지정한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfig {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    static {
        KAFKA.start();
    }

    /**
     * broker를 멈춘 채로 {@code action}을 실행하고, 끝나면 성공·실패와 관계없이 되살린다 (#339).
     * <p>
     * 컨테이너를 {@code docker pause}로 멈춰 연결이 끊긴 broker를 흉내 낸다. stop/start는 매핑 포트가 바뀌어
     * client가 다시 붙지 못하므로 쓰지 않는다. 컨테이너는 같은 JVM의 모든 테스트가 같이 쓰므로 멈춘 채로 두면 안 된다.
     * 멈춘 동안의 전송이 빨리 실패하게 하려면 테스트에서 producer {@code max.block.ms}·{@code delivery.timeout.ms}를 줄인다.
     */
    public static void withBrokerPaused(Runnable action) {
        var docker = KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            action.run();
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
    }

    @Bean
    DynamicPropertyRegistrar kafkaProperties() {
        return registry -> registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
