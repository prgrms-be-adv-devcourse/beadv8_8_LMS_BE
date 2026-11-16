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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CompositeRecordInterceptor;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.CompositeProducerListener;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * 알림 모듈 Kafka 소비 설정: 소비자 값, ConsumerFactory, DLT 발행용 ProducerFactory·KafkaTemplate, 리스너 ContainerFactory를 조립한다.
 * 채팅 설정({@code ChatKafkaConfig})을 복사했다(ND-028). 오류 처리는 {@link NotificationKafkaErrorHandlerConfig}, DLT 토픽 선언은
 * {@link NotificationKafkaTopicConfig}, 이름과 값은 {@link NotificationKafkaConstants}에 있다. 규칙은
 * {@code docs/architecture/infrastructure/kafka.md}다. common/kafka는 연결 설정만 두고, 업무 모듈은 {@code :kafka} 프로젝트를
 * 의존할 수 없어 운영 정책(재시도·DLT·역직렬화)을 이 모듈이 구현한다.
 * <ul>
 *     <li>소비자는 {@link NotificationKafkaConsumer} 여섯 개(회원 복제본, 출연·지갑·강의·고객센터·채팅 알림)다(ND-047·ND-069·ND-179).
 *     소비자마다 같은 빈 일곱 개를 {@link NotificationKafkaConsumerRegistrar}가 이 클래스의 조립 메서드로 등록한다(ND-180). 회원 말고는
 *     컨테이너를 첫 Backfill 뒤에 시작한다(ND-078).</li>
 *     <li>props는 이 클래스에 적고 연결 주소만 {@code spring.kafka.bootstrap-servers}에서 받는다.</li>
 *     <li>value 역직렬화는 신뢰 패키지 하나만 믿는 {@link JacksonJsonDeserializer}를 {@link TrustedEventDeserializer}와
 *     {@link ErrorHandlingDeserializer}로 감싼다. 깨진 메시지는 오류 처리기가 재시도 없이 DLT로 보낸다. 강의 이벤트만 값을 바이트로
 *     받고, 신뢰 타입 검사와 역직렬화를 리스너가 한다(원본 JSON의 키를 봐야 해서, ND-096).</li>
 *     <li>offset: 자동 커밋을 끄고 레코드마다(AckMode.RECORD) 리스너가 정상 반환한 뒤 커밋한다. 리스너는 DB 커밋 뒤에야 반환하므로
 *     offset은 언제나 DB 커밋 뒤다.</li>
 * </ul>
 * Kafka 빈은 기본 후보에서 빼서({@code defaultCandidate = false}) Boot의 기본 Kafka 빈이 물러나지 않게 하고, 이름으로만 쓴다.
 * 직접 만든 factory에도 Boot가 자기 factory에 적용하는 customizer(지표 listener 등)를 적용해 consumer lag·DLT 전송이 지표에 남는다.
 * <p>
 * 아래 이름 상수는 리스너({@code containerFactory})·설정 주입({@code @Qualifier})·시험({@code @TestBean})이 쓴다.
 * {@link NotificationKafkaConsumer}의 이름 규칙과 같다.
 */
@Configuration(proxyBeanMethods = false)
@Import(NotificationKafkaConsumerRegistrar.class)
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
    private final NotificationKafkaMetrics metrics;

    public NotificationKafkaConfig(@Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> consumerFactoryCustomizers,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers,
            NotificationKafkaMetrics metrics) {
        this.bootstrapServers = bootstrapServers;
        this.consumerFactoryCustomizers = consumerFactoryCustomizers;
        this.producerFactoryCustomizers = producerFactoryCustomizers;
        this.metrics = metrics;
    }

    /** 이벤트 값은 신뢰 패키지 역직렬화기로, 바이트 값은 그대로 받는다 */
    ConsumerFactory<String, ?> consumerFactory(NotificationKafkaConsumer consumer,
            NotificationKafkaConsumerSettings settings) {
        return switch (consumer.valueFormat()) {
            case EVENT -> consumerFactory(settings, eventDeserializer(consumer.trustedPackage()));
            case BYTES -> consumerFactory(settings, new ByteArrayDeserializer());
        };
    }

    /** 발행 실패 로그에 key·값(회원 정보 등)을 남기지 않는 DLT 템플릿. 전송 결과는 원본 토픽별로 센다 */
    KafkaTemplate<String, Object> deadLetterTemplate(ProducerFactory<String, Object> producerFactory,
            NotificationKafkaConsumerSettings settings) {
        return deadLetterTemplate(producerFactory, metrics.deadLetterListener(settings.topic()));
    }

    /**
     * 리스너 컨테이너 팩토리. 알림을 만드는 소비자 컨테이너는 앱 기동 때 시작하지 않고 첫 Backfill이 끝나면 시작한다(ND-078·ND-080).
     * 회원 복제본은 기동 때 시작한다.
     */
    <V> ConcurrentKafkaListenerContainerFactory<String, V> listenerContainerFactory(NotificationKafkaConsumer consumer,
            ConsumerFactory<String, V> consumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, V> factory = containerFactory(consumerFactory, errorHandler);
        if (consumer.startMode() == NotificationKafkaConsumer.StartMode.AFTER_FIRST_BACKFILL) {
            factory.setAutoStartup(false);
        }
        return factory;
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
    ProducerFactory<String, Object> deadLetterProducerFactory(NotificationKafkaConsumerSettings settings) {
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

    private <V> ConcurrentKafkaListenerContainerFactory<String, V> containerFactory(
            ConsumerFactory<String, V> consumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, V> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(NotificationKafkaConstants.CONCURRENCY);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // 직접 만든 팩토리에는 Boot의 spring.kafka.listener.observation-enabled가 적용되지 않는다. 받은 메시지의 traceparent를
        // 부모로 consumer span을 만들게 켠다
        factory.getContainerProperties().setObservationEnabled(true);
        // 처리 로그에 eventType·eventKey를 붙인다(docs/dev/logging.md "MDC 키"). 소비 지연과 저장 결과의 소비 문맥(ND-160·ND-161)
        factory.setRecordInterceptor(new CompositeRecordInterceptor<>(new EventLogContextRecordInterceptor<>(),
                metrics.<String, V>recordInterceptor()));
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

    /**
     * 발행 실패 로그에 key·값을 남기지 않는 DLT 템플릿. 값에 회원 정보 같은 업무 데이터가 있을 수 있다. 전송 결과는 브로커 응답으로
     * 센다({@code metricsListener}, ND-159)
     */
    static <K, V> KafkaTemplate<K, V> deadLetterTemplate(ProducerFactory<K, V> producerFactory,
            ProducerListener<K, V> metricsListener) {
        KafkaTemplate<K, V> template = new KafkaTemplate<>(producerFactory);
        LoggingProducerListener<K, V> producerListener = new LoggingProducerListener<>();
        producerListener.setIncludeContents(false);
        template.setProducerListener(new CompositeProducerListener<>(producerListener, metricsListener));
        // DLT 전송도 실패한 소비의 trace에 남긴다
        template.setObservationEnabled(true);
        return template;
    }
}
