package com.hapbang.auxiliary.chatbot.in.kafka;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 챗봇 모듈의 출연 이벤트 소비 설정 (#716). chat-app 소비 설정({@code ChatKafkaConfig})을 따른다.
 * <ul>
 *     <li>같은 앱의 다른 모듈(알림)도 Kafka를 받으므로 Boot 기본 컨테이너 팩토리·오류 처리기를 바꾸지 않고 챗봇 전용으로 만든다
 *     (기본 후보가 아님).</li>
 *     <li>값은 {@code __TypeId__} 헤더의 타입으로 읽고, 신뢰 패키지는 정확히 {@code com.hapbang.shared.appearance.event}다.
 *     읽을 수 없는 메시지(클래스 없음·형식 오류)는 다시 시도하지 않고 DLT로 보낸다.</li>
 *     <li>처리 실패는 1초부터 두 배씩(최대 10초) 3번 다시 시도한 뒤 {@code <topic>.<group>.DLT}의 같은 파티션으로 보낸다.
 *     값이 비어 있는 이벤트(필수 필드 없음)는 다시 시도하지 않는다.</li>
 *     <li>토픽 자동 생성은 끈다. 원본 토픽은 출연 앱이, DLT는 여기서 만든다.</li>
 * </ul>
 * {@value #ENABLED}로 끈다(모듈 테스트는 Kafka 테스트 밖에서 끈다).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = ChatbotKafkaConfig.ENABLED, matchIfMissing = true)
public class ChatbotKafkaConfig {

    public static final String ENABLED = "hapbang.chatbot.appearance-events.enabled";
    public static final String APPEARANCE_EVENT_SETTINGS = "chatbotAppearanceEventSettings";
    public static final String APPEARANCE_EVENT_CONTAINER_FACTORY = "chatbotAppearanceEventListenerContainerFactory";

    static final String DEAD_LETTER_PRODUCER_FACTORY = "deadLetterProducerFactory";

    private static final Duration DEAD_LETTER_SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEAD_LETTER_RETENTION = Duration.ofDays(7);

    private final String bootstrapServers;
    private final ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> consumerFactoryCustomizers;
    private final ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers;

    public ChatbotKafkaConfig(@Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> consumerFactoryCustomizers,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers) {
        this.bootstrapServers = bootstrapServers;
        this.consumerFactoryCustomizers = consumerFactoryCustomizers;
        this.producerFactoryCustomizers = producerFactoryCustomizers;
    }

    @Bean(name = APPEARANCE_EVENT_SETTINGS)
    ChatbotKafkaSettings chatbotAppearanceEventSettings() {
        return ChatbotKafkaSettings.appearanceDefaults();
    }

    @Bean(defaultCandidate = false)
    ConsumerFactory<String, Object> chatbotAppearanceEventConsumerFactory(
            @Qualifier(APPEARANCE_EVENT_SETTINGS) ChatbotKafkaSettings settings) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, settings.groupId(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        JacksonJsonDeserializer<Object> json = new JacksonJsonDeserializer<>();
        json.addTrustedPackages(ChatbotKafkaSettings.APPEARANCE_TRUSTED_PACKAGE);
        json.setRemoveTypeHeaders(false);
        DefaultKafkaConsumerFactory<String, Object> factory = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), new ErrorHandlingDeserializer<>(json));
        consumerFactoryCustomizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    @Bean(defaultCandidate = false)
    KafkaTemplate<String, Object> chatbotAppearanceEventDeadLetterTemplate() {
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
        // 빈이 아닌 DLT factory라 이름을 붙여 지표의 spring_id로 업무 발행 producer와 구분한다(kafka.md 재시도와 DLT)
        factory.setBeanName(DEAD_LETTER_PRODUCER_FACTORY);
        producerFactoryCustomizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(factory);
        // DLT로 보내는 실패 로그에 메시지 본문을 남기지 않는다
        LoggingProducerListener<String, Object> listener = new LoggingProducerListener<>();
        listener.setIncludeContents(false);
        template.setProducerListener(listener);
        template.setObservationEnabled(true);
        return template;
    }

    @Bean(defaultCandidate = false)
    DefaultErrorHandler chatbotAppearanceEventErrorHandler(
            @Qualifier(APPEARANCE_EVENT_SETTINGS) ChatbotKafkaSettings settings,
            @Qualifier("chatbotAppearanceEventDeadLetterTemplate") KafkaTemplate<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(settings.deadLetterTopic(), record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(DEAD_LETTER_SEND_TIMEOUT.plusSeconds(1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(settings.maxRetries());
        backOff.setInitialInterval(settings.retryInitialInterval().toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(settings.retryMaxInterval().toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 필수 필드가 빈 이벤트는 다시 읽어도 같다
        handler.addNotRetryableExceptions(NullPointerException.class, IllegalArgumentException.class);
        return handler;
    }

    @Bean(name = APPEARANCE_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    ConcurrentKafkaListenerContainerFactory<String, Object> chatbotAppearanceEventListenerContainerFactory(
            @Qualifier("chatbotAppearanceEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("chatbotAppearanceEventErrorHandler") DefaultErrorHandler errorHandler,
            ObjectProvider<RecordInterceptor<String, Object>> recordInterceptor) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
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

    @Bean
    NewTopic chatbotAppearanceEventDeadLetterTopic(@Qualifier(APPEARANCE_EVENT_SETTINGS) ChatbotKafkaSettings settings) {
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
