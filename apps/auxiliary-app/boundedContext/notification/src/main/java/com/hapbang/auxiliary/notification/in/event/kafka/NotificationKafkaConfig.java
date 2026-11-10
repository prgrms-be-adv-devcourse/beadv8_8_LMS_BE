package com.hapbang.auxiliary.notification.in.event.kafka;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
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
 * 알림 모듈 Kafka 소비 설정: 소비자 값, ConsumerFactory, DLT 발행용 ProducerFactory·KafkaTemplate, 리스너 ContainerFactory.
 * 채팅 설정({@code ChatKafkaConfig})을 복사했다(ND-028). 오류 처리는 {@link NotificationKafkaErrorHandlerConfig}, DLT 토픽 선언은
 * {@link NotificationKafkaTopicConfig}, 이름과 값은 {@link NotificationKafkaConstants}에 있다. 규칙은
 * {@code docs/architecture/infrastructure/kafka.md}다. common/kafka는 연결 설정만 두고, 업무 모듈은 {@code :kafka} 프로젝트를
 * 의존할 수 없어 운영 정책(재시도·DLT·역직렬화)을 이 모듈이 구현한다.
 * <ul>
 *     <li>지금 소비자는 회원 이벤트({@code member.events.v1}, 그룹 {@code auxiliary-app.notification-member-replica}) 하나다.
 *     알림을 만드는 소비자(N5 이후)는 아래 조립 메서드를 그대로 쓴다.</li>
 *     <li>props는 이 클래스에 적고 연결 주소만 {@code spring.kafka.bootstrap-servers}에서 받는다.</li>
 *     <li>value 역직렬화는 신뢰 패키지 하나만 믿는 {@link JacksonJsonDeserializer}를 {@link TrustedEventDeserializer}와
 *     {@link ErrorHandlingDeserializer}로 감싼다. 깨진 메시지는 오류 처리기가 재시도 없이 DLT로 보낸다.</li>
 *     <li>offset: 자동 커밋을 끄고 레코드마다(AckMode.RECORD) 리스너가 정상 반환한 뒤 커밋한다. 리스너는 DB 커밋 뒤에야 반환하므로
 *     offset은 언제나 DB 커밋 뒤다.</li>
 * </ul>
 * Kafka 빈은 기본 후보에서 빼서({@code defaultCandidate = false}) Boot의 기본 Kafka 빈이 물러나지 않게 하고, 이름으로만 쓴다.
 * 직접 만든 factory에도 Boot가 자기 factory에 적용하는 customizer(지표 listener 등)를 적용해 consumer lag·DLT 전송이 지표에 남는다.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationKafkaConfig {

    public static final String MEMBER_EVENT_SETTINGS = "notificationMemberEventSettings";
    public static final String MEMBER_EVENT_CONTAINER_FACTORY = "notificationMemberEventListenerContainerFactory";
    public static final String MEMBER_EVENT_DEAD_LETTER_TEMPLATE = "notificationMemberEventDeadLetterTemplate";

    private final String bootstrapServers;
    private final ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> consumerFactoryCustomizers;
    private final ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers;

    public NotificationKafkaConfig(@Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> consumerFactoryCustomizers,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers) {
        this.bootstrapServers = bootstrapServers;
        this.consumerFactoryCustomizers = consumerFactoryCustomizers;
        this.producerFactoryCustomizers = producerFactoryCustomizers;
    }

    // 회원 이벤트

    @Bean(name = MEMBER_EVENT_SETTINGS)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    public NotificationKafkaConsumerSettings notificationMemberEventSettings() {
        return NotificationKafkaConsumerSettings.of(NotificationKafkaConstants.MEMBER_EVENTS_TOPIC,
                NotificationKafkaConstants.MEMBER_REPLICA_GROUP);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    ConsumerFactory<String, Object> notificationMemberEventConsumerFactory(
            @Qualifier(MEMBER_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return consumerFactory(settings, NotificationKafkaConstants.MEMBER_EVENTS_TRUSTED_PACKAGE);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    public ProducerFactory<String, Object> notificationMemberEventDeadLetterProducerFactory(
            @Qualifier(MEMBER_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterProducerFactory(settings);
    }

    /** 발행 실패 로그에 key·값(회원 정보)을 남기지 않는 DLT 템플릿 */
    @Bean(name = MEMBER_EVENT_DEAD_LETTER_TEMPLATE, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    KafkaTemplate<String, Object> notificationMemberEventDeadLetterTemplate(
            @Qualifier("notificationMemberEventDeadLetterProducerFactory") ProducerFactory<String, Object> producerFactory) {
        return deadLetterTemplate(producerFactory);
    }

    @Bean(name = MEMBER_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, matchIfMissing = true)
    ConcurrentKafkaListenerContainerFactory<String, Object> notificationMemberEventListenerContainerFactory(
            @Qualifier("notificationMemberEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier(NotificationKafkaErrorHandlerConfig.MEMBER_EVENT_ERROR_HANDLER) DefaultErrorHandler errorHandler) {
        return containerFactory(consumerFactory, errorHandler);
    }

    // 소비자들이 함께 쓰는 조립

    private ConsumerFactory<String, Object> consumerFactory(NotificationKafkaConsumerSettings settings,
            String trustedPackage) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, settings.groupId(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, NotificationKafkaConstants.AUTO_OFFSET_RESET,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                // 운영 broker는 자동 생성이 꺼져 있다. 자동 생성이 켜진 시험 broker에서 소비자가 먼저 붙어 토픽을 만들지 않게 한다(kafka.md)
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        DefaultKafkaConsumerFactory<String, Object> factory = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), eventDeserializer(trustedPackage));
        consumerFactoryCustomizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    /**
     * DLT 발행 한 번이 {@link NotificationKafkaConsumerSettings#deadLetterSendTimeout()} 안에 끝나도록 메타데이터 대기
     * ({@code max.block.ms})에 절반, 전송 완료 대기에 나머지를 준다. DLT는 드물어 모아 보내지 않는다.
     */
    private ProducerFactory<String, Object> deadLetterProducerFactory(NotificationKafkaConsumerSettings settings) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, settings.deadLetterMaxBlockMillis(),
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) settings.deadLetterDeliveryMillis(),
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) settings.deadLetterDeliveryMillis(),
                ProducerConfig.LINGER_MS_CONFIG, 0);
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(props,
                new StringSerializer(), deadLetterValueSerializer());
        producerFactoryCustomizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    private static ConcurrentKafkaListenerContainerFactory<String, Object> containerFactory(
            ConsumerFactory<String, Object> consumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(NotificationKafkaConstants.CONCURRENCY);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // 직접 만든 팩토리에는 Boot의 spring.kafka.listener.observation-enabled가 적용되지 않는다. 받은 메시지의 traceparent를
        // 부모로 consumer span을 만들게 켠다
        factory.getContainerProperties().setObservationEnabled(true);
        // 처리 로그에 eventType·eventKey를 붙인다(docs/dev/logging.md "MDC 키")
        factory.setRecordInterceptor(new EventLogContextRecordInterceptor<>());
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
        // DLT 전송도 실패한 소비의 trace에 남긴다
        template.setObservationEnabled(true);
        return template;
    }
}
