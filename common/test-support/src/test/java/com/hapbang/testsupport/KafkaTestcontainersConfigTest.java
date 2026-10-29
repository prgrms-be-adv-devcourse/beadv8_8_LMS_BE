package com.hapbang.testsupport;

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
        "spring.kafka.consumer.auto-offset-reset=earliest"
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

    @Configuration(proxyBeanMethods = false)
    @Import(KafkaTestcontainersConfig.class)
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    static class Config {
    }
}
