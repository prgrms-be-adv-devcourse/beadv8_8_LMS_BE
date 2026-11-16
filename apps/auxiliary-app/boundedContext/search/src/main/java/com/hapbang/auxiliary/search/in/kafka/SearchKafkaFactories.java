package com.hapbang.auxiliary.search.in.kafka;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * 검색 Kafka 소비자(회원 #771, 강의 #882)가 함께 쓰는 조립. 규칙은 {@code docs/architecture/infrastructure/kafka.md}다. 소비자마다 설정 클래스
 * ({@link SearchKafkaConfig}, {@link SearchLectureKafkaConfig})가 이것으로 같은 모양의 빈을 만든다.
 * <ul>
 *     <li>새 그룹은 {@code earliest}부터 읽고, 자동 커밋·토픽 자동 생성을 끈다.</li>
 *     <li>처리 실패는 1초부터 두 배씩(최대 10초) 다시 시도한 뒤 {@code <topic>.<group>.DLT}의 같은 파티션으로 보낸다. 필수 값이 빠진 이벤트
 *     ({@link NullPointerException}, {@link IllegalArgumentException})는 다시 시도하지 않는다. 읽지 못한 메시지는 spring-kafka 기본 분류대로
 *     바로 DLT로 간다.</li>
 *     <li>offset은 레코드마다 리스너가 정상 반환한 뒤 커밋한다.</li>
 * </ul>
 */
final class SearchKafkaFactories {

    static final Duration DEAD_LETTER_SEND_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEAD_LETTER_RETENTION = Duration.ofDays(7);

    private SearchKafkaFactories() {
    }

    static <V> ConsumerFactory<String, V> consumerFactory(String bootstrapServers, SearchKafkaSettings settings,
            Deserializer<V> valueDeserializer, ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> customizers) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, settings.groupId(),
                // 새 그룹은 보존 기간 안의 과거 이벤트부터 읽는다
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        DefaultKafkaConsumerFactory<String, V> factory = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), valueDeserializer);
        customizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    /**
     * @param producerFactoryName 지표의 spring_id로 다른 producer와 구분하는 이름(빈이 아닌 DLT factory라 직접 붙인다)
     */
    static KafkaTemplate<String, Object> deadLetterTemplate(String bootstrapServers, String producerFactoryName,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> customizers) {
        long sendTimeout = DEAD_LETTER_SEND_TIMEOUT.toMillis();
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, sendTimeout,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) sendTimeout,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) sendTimeout,
                ProducerConfig.LINGER_MS_CONFIG, 0);
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(props,
                new StringSerializer(), deadLetterValueSerializer());
        factory.setBeanName(producerFactoryName);
        customizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(factory);
        // DLT로 보내는 실패 로그에 메시지 본문(닉네임·자기소개·강의 정보)을 남기지 않는다
        LoggingProducerListener<String, Object> listener = new LoggingProducerListener<>();
        listener.setIncludeContents(false);
        template.setProducerListener(listener);
        template.setObservationEnabled(true);
        return template;
    }

    static DefaultErrorHandler errorHandler(SearchKafkaSettings settings, KafkaTemplate<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(settings.deadLetterTopic(), record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(DEAD_LETTER_SEND_TIMEOUT.plusSeconds(1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(settings.maxRetries());
        backOff.setInitialInterval(settings.retryInitialInterval().toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(settings.retryMaxInterval().toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 필수 값이 빠진 이벤트는 다시 읽어도 같다
        handler.addNotRetryableExceptions(NullPointerException.class, IllegalArgumentException.class);
        return handler;
    }

    static <V> ConcurrentKafkaListenerContainerFactory<String, V> containerFactory(
            ConsumerFactory<String, V> consumerFactory, DefaultErrorHandler errorHandler,
            ObjectProvider<RecordInterceptor<String, V>> recordInterceptor) {
        ConcurrentKafkaListenerContainerFactory<String, V> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);
        // 직접 만든 팩토리라 common kafka 자동 설정의 MDC(eventType·eventKey) 인터셉터 빈을 직접 붙인다.
        // 업무 모듈은 :kafka 프로젝트를 의존할 수 없어(verifyModuleStructure) 클래스 대신 빈으로 받는다
        recordInterceptor.ifUnique(factory::setRecordInterceptor);
        return factory;
    }

    static NewTopic deadLetterTopic(SearchKafkaSettings settings) {
        return TopicBuilder.name(settings.deadLetterTopic())
                .partitions(settings.deadLetterPartitions())
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(DEAD_LETTER_RETENTION.toMillis()))
                .build();
    }

    // 읽지 못한 메시지(byte[])와 읽은 이벤트(객체)를 모두 DLT로 보낸다
    private static Serializer<Object> deadLetterValueSerializer() {
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JacksonJsonSerializer<>());
        return new DelegatingByTypeSerializer(delegates, true);
    }
}
