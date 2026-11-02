package com.hapbang.member.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 회원 이벤트 토픽 선언. 운영 broker처럼 토픽 자동 생성을 끈 전용 Kafka 컨테이너에 회원 앱의 Kafka 설정만 올려 확인한다.
 * DB와 다른 빈은 띄우지 않는다.
 */
class MemberKafkaConfigTest {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(SharedEventPackage.class, MemberKafkaConfig.class);

    @BeforeAll
    static void startKafka() {
        KAFKA.start();
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.stop();
    }

    @Test
    @DisplayName("자동 생성을 끈 broker에서도 회원 앱이 뜰 때 회원 이벤트 토픽을 partition 3개로 만든다")
    void createsDeclaredTopicWithoutAutoCreation() {
        contextRunner
                .withPropertyValues(
                        "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "spring.kafka.producer.properties.max.block.ms=3000")
                .run(context -> {
                    try (Admin admin = Admin.create(Map.of(
                            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
                        TopicDescription topic = admin.describeTopics(List.of(MemberKafkaConfig.MEMBER_EVENTS_TOPIC))
                                .allTopicNames().get(10, TimeUnit.SECONDS).get(MemberKafkaConfig.MEMBER_EVENTS_TOPIC);
                        assertThat(topic.partitions()).hasSize(3)
                                .allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));

                        // 이 broker는 선언하지 않은 토픽을 만들지 않는다 (위 토픽은 자동 생성으로 생긴 것이 아니다)
                        String undeclared = "undeclared-" + UUID.randomUUID();
                        KafkaTemplate<?, ?> template = context.getBean(KafkaTemplate.class);
                        assertThatThrownBy(() -> template.send(undeclared, null).get(10, TimeUnit.SECONDS))
                                .hasStackTraceContaining("not present in metadata");
                        assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).doesNotContain(undeclared);
                    }
                });
    }

    /** 외부화 설정이 shared 이벤트 패키지를 대상으로 삼도록 회원 앱과 같은 자동 설정 패키지를 둔다 */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackages = "com.hapbang.shared")
    static class SharedEventPackage {
    }
}
