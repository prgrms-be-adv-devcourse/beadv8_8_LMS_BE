package com.hapbang.chat.in.event.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * chat-app 소비자 하나의 Kafka 부품을 만든다. 각 소비 설정이 자기 이름으로 기본 후보가 아닌 빈을 등록할 때 쓴다
 * (Boot의 기본 ConsumerFactory·KafkaTemplate을 대신하지 않는다).
 * <ul>
 *     <li>key는 문자열, value는 {@link TrustedEventDeserializer}. 자동 커밋을 끄고 레코드마다(AckMode.RECORD) 리스너가 정상 반환한
 *     뒤 커밋한다. 리스너는 DB 커밋 뒤에야 반환하므로 offset은 언제나 DB 커밋 뒤다.</li>
 *     <li>처리에 실패하면 같은 자리에서 지수 간격(첫 간격부터 두 배씩, 상한까지)으로 블로킹 재시도한 뒤 그룹별 DLT로 보낸다.
 *     재시도하는 동안 그 partition의 뒤 메시지는 기다려 같은 key의 순서가 지켜진다. 역직렬화 실패 등 spring-kafka 기본 분류와
 *     넘긴 재시도 제외 예외는 바로 DLT로 보낸다.</li>
 * </ul>
 */
public final class ChatKafkaListenerFactories {

    private ChatKafkaListenerFactories() {
    }

    public static ConsumerFactory<String, Object> consumerFactory(KafkaProperties kafkaProperties,
            KafkaConnectionDetails connectionDetails, ChatKafkaConsumerSettings settings, String trustedPackage) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getConsumer().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, settings.groupId());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, settings.autoOffsetReset());
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(),
                TrustedEventDeserializer.errorHandling(trustedPackage));
    }

    public static ProducerFactory<String, Object> deadLetterProducerFactory(KafkaProperties kafkaProperties,
            KafkaConnectionDetails connectionDetails, ChatKafkaConsumerSettings settings) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties());
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getProducer().getBootstrapServers());
        return new DefaultKafkaProducerFactory<>(
                ChatDeadLetters.producerProperties(config, settings.deadLetterSendTimeout()),
                new StringSerializer(), ChatDeadLetters.valueSerializer());
    }

    @SafeVarargs
    public static ConcurrentKafkaListenerContainerFactory<String, Object> containerFactory(
            ConsumerFactory<String, Object> consumerFactory, KafkaTemplate<String, Object> deadLetterTemplate,
            ChatKafkaConsumerSettings settings, Class<? extends Exception>... notRetryable) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler(deadLetterTemplate, settings, notRetryable));
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @SafeVarargs
    static DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> deadLetterTemplate,
            ChatKafkaConsumerSettings settings, Class<? extends Exception>... notRetryable) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(settings.maxRetries().intValue());
        backOff.setInitialInterval(settings.retryInitialInterval().toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(settings.retryMaxInterval().toMillis());
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                ChatDeadLetters.recoverer(deadLetterTemplate, settings), backOff);
        if (notRetryable.length > 0) {
            errorHandler.addNotRetryableExceptions(notRetryable);
        }
        return errorHandler;
    }
}
