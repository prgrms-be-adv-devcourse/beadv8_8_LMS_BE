package com.hapbang.chat.in.event.kafka;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * chat-app Kafka 소비 설정: 소비자 값, ConsumerFactory, DLT 발행용 ProducerFactory·KafkaTemplate, 리스너 ContainerFactory.
 * 오류 처리는 {@link ChatKafkaErrorHandlerConfig}, DLT 토픽 선언은 {@link ChatKafkaTopicConfig}, 이름과 값은
 * {@link ChatKafkaConstants}에 있다. 규칙은 {@code docs/architecture/infrastructure/kafka.md}다. {@code common/kafka}는 연결 설정만
 * 두므로 운영 정책(재시도·DLT·역직렬화)은 chat-app이 구현한다.
 * <ul>
 *     <li>회원 이벤트({@code member.events.v1}, 그룹 {@code chat-app.member-replica})와 출연 이벤트({@code appearance.events.v1},
 *     그룹 {@code chat-app.appearance-notification}) 두 소비자가 같은 구조를 쓴다.</li>
 *     <li>props는 이 클래스에 적고 연결 주소만 {@code spring.kafka.bootstrap-servers}에서 받는다(없으면 Spring Boot 기본값과 같은
 *     {@code localhost:9092}).</li>
 *     <li>value 역직렬화는 신뢰 패키지 하나만 믿는 {@link JacksonJsonDeserializer}를 {@link TrustedEventDeserializer}와
 *     {@link ErrorHandlingDeserializer}로 감싼다. 깨진 메시지는 오류 처리기가 재시도 없이 DLT로 보낸다.</li>
 *     <li>offset: 자동 커밋을 끄고 레코드마다(AckMode.RECORD) 리스너가 정상 반환한 뒤 커밋한다. 리스너는 DB 커밋 뒤에야 반환하므로
 *     offset은 언제나 DB 커밋 뒤다.</li>
 * </ul>
 * 소비자마다 {@code hapbang.chat.member-events.enabled}·{@code hapbang.chat.appearance-events.enabled}로 켜고 끈다(없으면 켜짐).
 * Kafka 빈은 기본 후보에서 빼서({@code defaultCandidate = false}) Boot의 기본 Kafka 빈이 물러나지 않게 하고, 이름으로만 쓴다.
 */
@Configuration(proxyBeanMethods = false)
public class ChatKafkaConfig {

    public static final String MEMBER_EVENT_SETTINGS = "chatMemberEventSettings";
    public static final String MEMBER_EVENT_CONTAINER_FACTORY = "chatMemberEventListenerContainerFactory";
    public static final String MEMBER_EVENT_DEAD_LETTER_TEMPLATE = "chatMemberEventDeadLetterTemplate";

    public static final String APPEARANCE_EVENT_SETTINGS = "chatAppearanceEventSettings";
    public static final String APPEARANCE_EVENT_CONTAINER_FACTORY = "chatAppearanceEventListenerContainerFactory";
    public static final String APPEARANCE_EVENT_DEAD_LETTER_TEMPLATE = "chatAppearanceEventDeadLetterTemplate";

    private final String bootstrapServers;

    public ChatKafkaConfig(@Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    // 회원 이벤트

    @Bean(name = MEMBER_EVENT_SETTINGS)
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    ChatKafkaConsumerSettings chatMemberEventSettings() {
        return new ChatKafkaConsumerSettings(
                ChatKafkaConstants.MEMBER_EVENTS_TOPIC,
                ChatKafkaConstants.MEMBER_REPLICA_GROUP,
                ChatKafkaConstants.MEMBER_EVENTS_DLT,
                ChatKafkaConstants.MAX_RETRIES,
                ChatKafkaConstants.RETRY_INITIAL_INTERVAL,
                ChatKafkaConstants.RETRY_MAX_INTERVAL,
                ChatKafkaConstants.DEAD_LETTER_SEND_TIMEOUT,
                ChatKafkaConstants.DEAD_LETTER_PARTITIONS,
                ChatKafkaConstants.DEAD_LETTER_REPLICAS);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    ConsumerFactory<String, Object> chatMemberEventConsumerFactory(
            @Qualifier(MEMBER_EVENT_SETTINGS) ChatKafkaConsumerSettings settings) {
        return consumerFactory(settings, ChatKafkaConstants.MEMBER_EVENTS_TRUSTED_PACKAGE);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    ProducerFactory<String, Object> chatMemberEventDeadLetterProducerFactory(
            @Qualifier(MEMBER_EVENT_SETTINGS) ChatKafkaConsumerSettings settings) {
        return deadLetterProducerFactory(settings);
    }

    /** 발행 실패 로그에 key·값(회원 정보)을 남기지 않는 DLT 템플릿 */
    @Bean(name = MEMBER_EVENT_DEAD_LETTER_TEMPLATE, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    KafkaTemplate<String, Object> chatMemberEventDeadLetterTemplate(
            @Qualifier("chatMemberEventDeadLetterProducerFactory") ProducerFactory<String, Object> producerFactory) {
        return deadLetterTemplate(producerFactory);
    }

    @Bean(name = MEMBER_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    ConcurrentKafkaListenerContainerFactory<String, Object> chatMemberEventListenerContainerFactory(
            @Qualifier("chatMemberEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier(ChatKafkaErrorHandlerConfig.MEMBER_EVENT_ERROR_HANDLER) DefaultErrorHandler errorHandler) {
        return containerFactory(consumerFactory, errorHandler);
    }

    // 출연 이벤트

    @Bean(name = APPEARANCE_EVENT_SETTINGS)
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    ChatKafkaConsumerSettings chatAppearanceEventSettings() {
        return new ChatKafkaConsumerSettings(
                ChatKafkaConstants.APPEARANCE_EVENTS_TOPIC,
                ChatKafkaConstants.APPEARANCE_NOTIFICATION_GROUP,
                ChatKafkaConstants.APPEARANCE_EVENTS_DLT,
                ChatKafkaConstants.MAX_RETRIES,
                ChatKafkaConstants.RETRY_INITIAL_INTERVAL,
                ChatKafkaConstants.RETRY_MAX_INTERVAL,
                ChatKafkaConstants.DEAD_LETTER_SEND_TIMEOUT,
                ChatKafkaConstants.DEAD_LETTER_PARTITIONS,
                ChatKafkaConstants.DEAD_LETTER_REPLICAS);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    ConsumerFactory<String, Object> chatAppearanceEventConsumerFactory(
            @Qualifier(APPEARANCE_EVENT_SETTINGS) ChatKafkaConsumerSettings settings) {
        return consumerFactory(settings, ChatKafkaConstants.APPEARANCE_EVENTS_TRUSTED_PACKAGE);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    ProducerFactory<String, Object> chatAppearanceEventDeadLetterProducerFactory(
            @Qualifier(APPEARANCE_EVENT_SETTINGS) ChatKafkaConsumerSettings settings) {
        return deadLetterProducerFactory(settings);
    }

    /** 발행 실패 로그에 key·값을 남기지 않는 DLT 템플릿 */
    @Bean(name = APPEARANCE_EVENT_DEAD_LETTER_TEMPLATE, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    KafkaTemplate<String, Object> chatAppearanceEventDeadLetterTemplate(
            @Qualifier("chatAppearanceEventDeadLetterProducerFactory") ProducerFactory<String, Object> producerFactory) {
        return deadLetterTemplate(producerFactory);
    }

    @Bean(name = APPEARANCE_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = ChatKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    ConcurrentKafkaListenerContainerFactory<String, Object> chatAppearanceEventListenerContainerFactory(
            @Qualifier("chatAppearanceEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier(ChatKafkaErrorHandlerConfig.APPEARANCE_EVENT_ERROR_HANDLER) DefaultErrorHandler errorHandler) {
        return containerFactory(consumerFactory, errorHandler);
    }

    // 두 소비자가 함께 쓰는 조립

    private ConsumerFactory<String, Object> consumerFactory(ChatKafkaConsumerSettings settings, String trustedPackage) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, settings.groupId(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, ChatKafkaConstants.AUTO_OFFSET_RESET,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), eventDeserializer(trustedPackage));
    }

    /**
     * DLT 발행 한 번이 {@link ChatKafkaConsumerSettings#deadLetterSendTimeout()} 안에 끝나도록 메타데이터 대기
     * ({@code max.block.ms}, DLT가 없으면 여기서 막힌다)에 절반, 전송 완료 대기에 나머지를 준다. DLT는 드물어 모아 보내지 않는다
     * ({@code delivery.timeout.ms >= linger.ms + request.timeout.ms}).
     */
    private ProducerFactory<String, Object> deadLetterProducerFactory(ChatKafkaConsumerSettings settings) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, settings.deadLetterMaxBlockMillis(),
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) settings.deadLetterDeliveryMillis(),
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) settings.deadLetterDeliveryMillis(),
                ProducerConfig.LINGER_MS_CONFIG, 0);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), deadLetterValueSerializer());
    }

    private static ConcurrentKafkaListenerContainerFactory<String, Object> containerFactory(
            ConsumerFactory<String, Object> consumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * 이벤트 value 역직렬화기. {@code __TypeId__} 헤더의 클래스 이름으로 타입을 정하고, 신뢰 패키지는 넘긴 실제 이벤트 패키지
     * 하나다. 헤더를 지우지 않아 본문을 읽지 못해 DLT로 보낸 원본에 타입이 남는다(고쳐 다시 넣을 수 있다).
     */
    static ErrorHandlingDeserializer<Object> eventDeserializer(String trustedPackage) {
        JacksonJsonDeserializer<Object> json = new JacksonJsonDeserializer<>();
        json.addTrustedPackages(trustedPackage);
        json.setRemoveTypeHeaders(false);
        return new ErrorHandlingDeserializer<>(new TrustedEventDeserializer(json, trustedPackage));
    }

    /** 읽지 못한 원본({@code byte[]})은 그대로, 읽은 레코드는 JSON과 {@code __TypeId__} 하나로 보낸다 */
    static Serializer<Object> deadLetterValueSerializer() {
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JacksonJsonSerializer<>());
        return new DelegatingByTypeSerializer(delegates, true);
    }

    /** 발행 실패 로그에 key·값을 남기지 않는 DLT 템플릿. 값에 회원 정보 같은 업무 데이터가 있을 수 있다 */
    static <K, V> KafkaTemplate<K, V> deadLetterTemplate(ProducerFactory<K, V> producerFactory) {
        KafkaTemplate<K, V> template = new KafkaTemplate<>(producerFactory);
        LoggingProducerListener<K, V> producerListener = new LoggingProducerListener<>();
        producerListener.setIncludeContents(false);
        template.setProducerListener(producerListener);
        return template;
    }
}
