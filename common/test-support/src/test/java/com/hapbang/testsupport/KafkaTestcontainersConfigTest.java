package com.hapbang.testsupport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(KafkaTestcontainersConfigTest.Config.class)
@TestPropertySource(properties = {
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        // broker가 멈춘 동안의 전송이 기본값(메타데이터 60초, 전달 120초) 대신 몇 초 안에 실패로 끝나게 한다
        "spring.kafka.producer.properties.max.block.ms=3000",
        "spring.kafka.producer.properties.request.timeout.ms=2000",
        "spring.kafka.producer.properties.delivery.timeout.ms=5000",
        "spring.kafka.producer.properties.linger.ms=0"
})
class KafkaTestcontainersConfigTest {

    @Autowired
    private KafkaTemplate<Object, Object> template;

    @Autowired
    private ConsumerFactory<Object, Object> consumerFactory;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    void kafka설정만으로_메시지를_발행하고_수신한다() throws Exception {
        MessagingContainerAssertions.assertKafkaRoundTrip(template, consumerFactory, kafkaAdmin);
    }

    @Test
    void broker를_멈춘_동안에는_전송이_실패하고_되살리면_다시_보낸다() throws Exception {
        String topic = "pause-" + UUID.randomUUID();
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        template.send(topic, "key", "before").get(10, TimeUnit.SECONDS);

        KafkaTestcontainersConfig.withBrokerPaused(() -> assertThatThrownBy(
                () -> template.send(topic, "key", "paused").get(20, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> template.send(topic, "key", "resumed").get(10, TimeUnit.SECONDS));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(KafkaTestcontainersConfig.class)
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    static class Config {
    }
}
