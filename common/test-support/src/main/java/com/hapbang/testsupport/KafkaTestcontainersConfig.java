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

    @Bean
    DynamicPropertyRegistrar kafkaProperties() {
        return registry -> registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
