package com.hapbang.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(MessagingTestcontainersConfigTest.Config.class)
@TestPropertySource(properties = {
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class MessagingTestcontainersConfigTest {

    @Autowired
    private KafkaTemplate<Object, Object> template;

    @Autowired
    private ConsumerFactory<Object, Object> consumerFactory;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void 두_설정을_같이_가져와도_각_컨테이너와_통신한다() throws Exception {
        MessagingContainerAssertions.assertKafkaRoundTrip(template, consumerFactory, kafkaAdmin);
        MessagingContainerAssertions.assertRedisRoundTrip(redis);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({KafkaTestcontainersConfig.class, RedisTestcontainersConfig.class})
    @ImportAutoConfiguration({KafkaAutoConfiguration.class, DataRedisAutoConfiguration.class})
    static class Config {
    }
}
