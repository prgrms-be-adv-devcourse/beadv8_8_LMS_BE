package com.hapbang.auxiliary.notification.in.event.kafka;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
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
 *     <li>소비자는 회원 이벤트({@code member.events.v1}, 그룹 {@code auxiliary-app.notification-member-replica}, 가입 환영·승급
 *     승인 포함 ND-080), 출연 이벤트({@code appearance.events.v1}, {@code auxiliary-app.notification-appearance-alert}), 지갑
 *     이벤트({@code billing.events.v1}, {@code auxiliary-app.notification-billing-alert}), 강의 이벤트({@code lecture.events.v1},
 *     {@code auxiliary-app.notification-lecture-alert}, N6 ND-087), 고객센터 이벤트({@code customersupport.events.v1},
 *     {@code auxiliary-app.notification-customersupport-alert}, N7 ND-122), 채팅 이벤트({@code chat.events.v1},
 *     {@code auxiliary-app.notification-chat-alert}, N9 ND-129) 여섯이다(ND-047·ND-069). 출연·지갑·강의·고객센터·채팅
 *     컨테이너는 첫 Backfill 뒤에 시작한다(ND-078).</li>
 *     <li>props는 이 클래스에 적고 연결 주소만 {@code spring.kafka.bootstrap-servers}에서 받는다.</li>
 *     <li>value 역직렬화는 신뢰 패키지 하나만 믿는 {@link JacksonJsonDeserializer}를 {@link TrustedEventDeserializer}와
 *     {@link ErrorHandlingDeserializer}로 감싼다. 깨진 메시지는 오류 처리기가 재시도 없이 DLT로 보낸다. 강의 이벤트만 값을 바이트로
 *     받고, 신뢰 타입 검사와 역직렬화를 리스너가 한다(원본 JSON의 키를 봐야 해서, ND-096).</li>
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
    public static final String APPEARANCE_EVENT_SETTINGS = "notificationAppearanceEventSettings";
    public static final String APPEARANCE_EVENT_CONTAINER_FACTORY = "notificationAppearanceEventListenerContainerFactory";
    public static final String APPEARANCE_EVENT_DEAD_LETTER_TEMPLATE = "notificationAppearanceEventDeadLetterTemplate";
    public static final String BILLING_EVENT_SETTINGS = "notificationBillingEventSettings";
    public static final String BILLING_EVENT_CONTAINER_FACTORY = "notificationBillingEventListenerContainerFactory";
    public static final String BILLING_EVENT_DEAD_LETTER_TEMPLATE = "notificationBillingEventDeadLetterTemplate";
    public static final String LECTURE_EVENT_SETTINGS = "notificationLectureEventSettings";
    public static final String LECTURE_EVENT_CONTAINER_FACTORY = "notificationLectureEventListenerContainerFactory";
    public static final String LECTURE_EVENT_DEAD_LETTER_TEMPLATE = "notificationLectureEventDeadLetterTemplate";
    public static final String CUSTOMERSUPPORT_EVENT_SETTINGS = "notificationCustomerSupportEventSettings";
    public static final String CUSTOMERSUPPORT_EVENT_CONTAINER_FACTORY =
            "notificationCustomerSupportEventListenerContainerFactory";
    public static final String CUSTOMERSUPPORT_EVENT_DEAD_LETTER_TEMPLATE =
            "notificationCustomerSupportEventDeadLetterTemplate";
    public static final String CHAT_EVENT_SETTINGS = "notificationChatEventSettings";
    public static final String CHAT_EVENT_CONTAINER_FACTORY = "notificationChatEventListenerContainerFactory";
    public static final String CHAT_EVENT_DEAD_LETTER_TEMPLATE = "notificationChatEventDeadLetterTemplate";

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

    // 출연 이벤트 → 알림(N5). 컨테이너는 첫 Backfill 뒤에 시작한다(ND-078, autoStartup false)

    @Bean(name = APPEARANCE_EVENT_SETTINGS)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    public NotificationKafkaConsumerSettings notificationAppearanceEventSettings() {
        return NotificationKafkaConsumerSettings.of(NotificationKafkaConstants.APPEARANCE_EVENTS_TOPIC,
                NotificationKafkaConstants.APPEARANCE_ALERT_GROUP);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    ConsumerFactory<String, Object> notificationAppearanceEventConsumerFactory(
            @Qualifier(APPEARANCE_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return consumerFactory(settings, NotificationKafkaConstants.APPEARANCE_EVENTS_TRUSTED_PACKAGE);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    public ProducerFactory<String, Object> notificationAppearanceEventDeadLetterProducerFactory(
            @Qualifier(APPEARANCE_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterProducerFactory(settings);
    }

    @Bean(name = APPEARANCE_EVENT_DEAD_LETTER_TEMPLATE, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    KafkaTemplate<String, Object> notificationAppearanceEventDeadLetterTemplate(
            @Qualifier("notificationAppearanceEventDeadLetterProducerFactory")
            ProducerFactory<String, Object> producerFactory) {
        return deadLetterTemplate(producerFactory);
    }

    @Bean(name = APPEARANCE_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, matchIfMissing = true)
    ConcurrentKafkaListenerContainerFactory<String, Object> notificationAppearanceEventListenerContainerFactory(
            @Qualifier("notificationAppearanceEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier(NotificationKafkaErrorHandlerConfig.APPEARANCE_EVENT_ERROR_HANDLER)
            DefaultErrorHandler errorHandler) {
        return alertContainerFactory(consumerFactory, errorHandler);
    }

    // 지갑 이벤트 → 알림(N5). 컨테이너는 첫 Backfill 뒤에 시작한다(ND-078, autoStartup false)

    @Bean(name = BILLING_EVENT_SETTINGS)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.BILLING_EVENTS_ENABLED, matchIfMissing = true)
    public NotificationKafkaConsumerSettings notificationBillingEventSettings() {
        return NotificationKafkaConsumerSettings.of(NotificationKafkaConstants.BILLING_EVENTS_TOPIC,
                NotificationKafkaConstants.BILLING_ALERT_GROUP);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.BILLING_EVENTS_ENABLED, matchIfMissing = true)
    ConsumerFactory<String, Object> notificationBillingEventConsumerFactory(
            @Qualifier(BILLING_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return consumerFactory(settings, NotificationKafkaConstants.BILLING_EVENTS_TRUSTED_PACKAGE);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.BILLING_EVENTS_ENABLED, matchIfMissing = true)
    public ProducerFactory<String, Object> notificationBillingEventDeadLetterProducerFactory(
            @Qualifier(BILLING_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterProducerFactory(settings);
    }

    @Bean(name = BILLING_EVENT_DEAD_LETTER_TEMPLATE, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.BILLING_EVENTS_ENABLED, matchIfMissing = true)
    KafkaTemplate<String, Object> notificationBillingEventDeadLetterTemplate(
            @Qualifier("notificationBillingEventDeadLetterProducerFactory")
            ProducerFactory<String, Object> producerFactory) {
        return deadLetterTemplate(producerFactory);
    }

    @Bean(name = BILLING_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.BILLING_EVENTS_ENABLED, matchIfMissing = true)
    ConcurrentKafkaListenerContainerFactory<String, Object> notificationBillingEventListenerContainerFactory(
            @Qualifier("notificationBillingEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier(NotificationKafkaErrorHandlerConfig.BILLING_EVENT_ERROR_HANDLER)
            DefaultErrorHandler errorHandler) {
        return alertContainerFactory(consumerFactory, errorHandler);
    }

    // 강의 이벤트 → 알림(N6). 값을 바이트로 받아 리스너가 #818 이전 형식을 판별한 뒤 레코드로 바꾼다(ND-086·ND-096).
    // 컨테이너는 첫 Backfill 뒤에 시작한다(ND-078·ND-087, autoStartup false)

    @Bean(name = LECTURE_EVENT_SETTINGS)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, matchIfMissing = true)
    public NotificationKafkaConsumerSettings notificationLectureEventSettings() {
        return NotificationKafkaConsumerSettings.of(NotificationKafkaConstants.LECTURE_EVENTS_TOPIC,
                NotificationKafkaConstants.LECTURE_ALERT_GROUP);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, matchIfMissing = true)
    ConsumerFactory<String, byte[]> notificationLectureEventConsumerFactory(
            @Qualifier(LECTURE_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return consumerFactory(settings, new ByteArrayDeserializer());
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, matchIfMissing = true)
    public ProducerFactory<String, Object> notificationLectureEventDeadLetterProducerFactory(
            @Qualifier(LECTURE_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterProducerFactory(settings);
    }

    @Bean(name = LECTURE_EVENT_DEAD_LETTER_TEMPLATE, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, matchIfMissing = true)
    KafkaTemplate<String, Object> notificationLectureEventDeadLetterTemplate(
            @Qualifier("notificationLectureEventDeadLetterProducerFactory")
            ProducerFactory<String, Object> producerFactory) {
        return deadLetterTemplate(producerFactory);
    }

    @Bean(name = LECTURE_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, matchIfMissing = true)
    ConcurrentKafkaListenerContainerFactory<String, byte[]> notificationLectureEventListenerContainerFactory(
            @Qualifier("notificationLectureEventConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory,
            @Qualifier(NotificationKafkaErrorHandlerConfig.LECTURE_EVENT_ERROR_HANDLER)
            DefaultErrorHandler errorHandler) {
        return alertContainerFactory(consumerFactory, errorHandler);
    }

    // 고객센터 이벤트 → 알림(N7, ND-122). 컨테이너는 첫 Backfill 뒤에 시작한다(ND-078, autoStartup false)

    @Bean(name = CUSTOMERSUPPORT_EVENT_SETTINGS)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_ENABLED, matchIfMissing = true)
    public NotificationKafkaConsumerSettings notificationCustomerSupportEventSettings() {
        return NotificationKafkaConsumerSettings.of(NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_TOPIC,
                NotificationKafkaConstants.CUSTOMERSUPPORT_ALERT_GROUP);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_ENABLED, matchIfMissing = true)
    ConsumerFactory<String, Object> notificationCustomerSupportEventConsumerFactory(
            @Qualifier(CUSTOMERSUPPORT_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return consumerFactory(settings, NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_TRUSTED_PACKAGE);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_ENABLED, matchIfMissing = true)
    public ProducerFactory<String, Object> notificationCustomerSupportEventDeadLetterProducerFactory(
            @Qualifier(CUSTOMERSUPPORT_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterProducerFactory(settings);
    }

    @Bean(name = CUSTOMERSUPPORT_EVENT_DEAD_LETTER_TEMPLATE, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_ENABLED, matchIfMissing = true)
    KafkaTemplate<String, Object> notificationCustomerSupportEventDeadLetterTemplate(
            @Qualifier("notificationCustomerSupportEventDeadLetterProducerFactory")
            ProducerFactory<String, Object> producerFactory) {
        return deadLetterTemplate(producerFactory);
    }

    @Bean(name = CUSTOMERSUPPORT_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_ENABLED, matchIfMissing = true)
    ConcurrentKafkaListenerContainerFactory<String, Object> notificationCustomerSupportEventListenerContainerFactory(
            @Qualifier("notificationCustomerSupportEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier(NotificationKafkaErrorHandlerConfig.CUSTOMERSUPPORT_EVENT_ERROR_HANDLER)
            DefaultErrorHandler errorHandler) {
        return alertContainerFactory(consumerFactory, errorHandler);
    }

    // 채팅 이벤트 → 알림(N9). 새 토픽 chat.events.v1을 새 그룹으로 읽는다(ND-129). 컨테이너는 첫 Backfill 뒤에 시작한다
    // (ND-078, autoStartup false)

    @Bean(name = CHAT_EVENT_SETTINGS)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CHAT_EVENTS_ENABLED, matchIfMissing = true)
    public NotificationKafkaConsumerSettings notificationChatEventSettings() {
        return NotificationKafkaConsumerSettings.of(NotificationKafkaConstants.CHAT_EVENTS_TOPIC,
                NotificationKafkaConstants.CHAT_ALERT_GROUP);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CHAT_EVENTS_ENABLED, matchIfMissing = true)
    ConsumerFactory<String, Object> notificationChatEventConsumerFactory(
            @Qualifier(CHAT_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return consumerFactory(settings, NotificationKafkaConstants.CHAT_EVENTS_TRUSTED_PACKAGE);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CHAT_EVENTS_ENABLED, matchIfMissing = true)
    public ProducerFactory<String, Object> notificationChatEventDeadLetterProducerFactory(
            @Qualifier(CHAT_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings) {
        return deadLetterProducerFactory(settings);
    }

    @Bean(name = CHAT_EVENT_DEAD_LETTER_TEMPLATE, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CHAT_EVENTS_ENABLED, matchIfMissing = true)
    KafkaTemplate<String, Object> notificationChatEventDeadLetterTemplate(
            @Qualifier("notificationChatEventDeadLetterProducerFactory")
            ProducerFactory<String, Object> producerFactory) {
        return deadLetterTemplate(producerFactory);
    }

    @Bean(name = CHAT_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    @ConditionalOnBooleanProperty(name = NotificationKafkaConstants.CHAT_EVENTS_ENABLED, matchIfMissing = true)
    ConcurrentKafkaListenerContainerFactory<String, Object> notificationChatEventListenerContainerFactory(
            @Qualifier("notificationChatEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier(NotificationKafkaErrorHandlerConfig.CHAT_EVENT_ERROR_HANDLER)
            DefaultErrorHandler errorHandler) {
        return alertContainerFactory(consumerFactory, errorHandler);
    }

    // 소비자들이 함께 쓰는 조립

    /** 알림을 만드는 출연·지갑·강의·고객센터·채팅 소비자 컨테이너. 앱 기동 때 시작하지 않고 첫 Backfill이 끝나면 시작한다(ND-078·ND-080) */
    private static <V> ConcurrentKafkaListenerContainerFactory<String, V> alertContainerFactory(
            ConsumerFactory<String, V> consumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, V> factory = containerFactory(consumerFactory, errorHandler);
        factory.setAutoStartup(false);
        return factory;
    }

    private ConsumerFactory<String, Object> consumerFactory(NotificationKafkaConsumerSettings settings,
            String trustedPackage) {
        return consumerFactory(settings, eventDeserializer(trustedPackage));
    }

    private <V> ConsumerFactory<String, V> consumerFactory(NotificationKafkaConsumerSettings settings,
            Deserializer<V> valueDeserializer) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, settings.groupId(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, NotificationKafkaConstants.AUTO_OFFSET_RESET,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                // 운영 broker는 자동 생성이 꺼져 있다. 자동 생성이 켜진 시험 broker에서 소비자가 먼저 붙어 토픽을 만들지 않게 한다(kafka.md)
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        DefaultKafkaConsumerFactory<String, V> factory = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), valueDeserializer);
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

    private static <V> ConcurrentKafkaListenerContainerFactory<String, V> containerFactory(
            ConsumerFactory<String, V> consumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, V> factory = new ConcurrentKafkaListenerContainerFactory<>();
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
