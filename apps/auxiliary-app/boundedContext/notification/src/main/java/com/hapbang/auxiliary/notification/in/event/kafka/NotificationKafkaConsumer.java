package com.hapbang.auxiliary.notification.in.event.kafka;

import org.springframework.core.env.PropertyResolver;

/**
 * 알림 Kafka 소비자 정의 목록(ND-179·ND-180). 소비자마다 같은 빈 일곱 개를 {@link NotificationKafkaConsumerRegistrar}가 이 목록으로 등록한다.
 * 소비자마다 다른 점은 값 형식과 시작 방식 두 가지다. 상수 순서가 등록 순서이고, 리팩토링 전 설정 클래스의 메서드 순서와 같다.
 * <p>
 * Bean 이름은 {@code notification<이름>Event<역할>} 규칙이다. 리스너·시험이 이름으로 받는 빈의 이름 상수는
 * {@link NotificationKafkaConfig}·{@link NotificationKafkaErrorHandlerConfig}에 그대로 있고, 이 규칙과 같다(구성 시험
 * {@code NotificationKafkaConsumerConfigurationTest}가 이름을 문자열로 확인한다).
 */
public enum NotificationKafkaConsumer {

    /** 회원 복제본(가입 환영·승급 승인 알림 포함, ND-080). 첫 Backfill 전에도 복제본을 맞춰야 해서 앱 기동 때 시작한다 */
    MEMBER("Member", NotificationKafkaConstants.MEMBER_EVENTS_TOPIC, NotificationKafkaConstants.MEMBER_REPLICA_GROUP,
            NotificationKafkaConstants.MEMBER_EVENTS_TRUSTED_PACKAGE, ValueFormat.EVENT, StartMode.ON_STARTUP,
            NotificationKafkaConstants.MEMBER_EVENTS_ENABLED),
    /** 출연 알림(N5) */
    APPEARANCE("Appearance", NotificationKafkaConstants.APPEARANCE_EVENTS_TOPIC,
            NotificationKafkaConstants.APPEARANCE_ALERT_GROUP, NotificationKafkaConstants.APPEARANCE_EVENTS_TRUSTED_PACKAGE,
            ValueFormat.EVENT, StartMode.AFTER_FIRST_BACKFILL, NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED),
    /** 지갑 알림(N5) */
    BILLING("Billing", NotificationKafkaConstants.BILLING_EVENTS_TOPIC, NotificationKafkaConstants.BILLING_ALERT_GROUP,
            NotificationKafkaConstants.BILLING_EVENTS_TRUSTED_PACKAGE, ValueFormat.EVENT, StartMode.AFTER_FIRST_BACKFILL,
            NotificationKafkaConstants.BILLING_EVENTS_ENABLED),
    /** 강의 알림(N6). 원본 JSON의 키를 봐야 해서 값을 바이트로 받고 리스너가 신뢰 타입 검사와 역직렬화를 한다(ND-096) */
    LECTURE("Lecture", NotificationKafkaConstants.LECTURE_EVENTS_TOPIC, NotificationKafkaConstants.LECTURE_ALERT_GROUP,
            NotificationKafkaConstants.LECTURE_EVENTS_TRUSTED_PACKAGE, ValueFormat.BYTES, StartMode.AFTER_FIRST_BACKFILL,
            NotificationKafkaConstants.LECTURE_EVENTS_ENABLED),
    /** 고객센터 알림(N7, ND-122) */
    CUSTOMERSUPPORT("CustomerSupport", NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_TOPIC,
            NotificationKafkaConstants.CUSTOMERSUPPORT_ALERT_GROUP,
            NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_TRUSTED_PACKAGE, ValueFormat.EVENT,
            StartMode.AFTER_FIRST_BACKFILL, NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_ENABLED),
    /** 채팅 단체방 알림(N9, ND-129) */
    CHAT("Chat", NotificationKafkaConstants.CHAT_EVENTS_TOPIC, NotificationKafkaConstants.CHAT_ALERT_GROUP,
            NotificationKafkaConstants.CHAT_EVENTS_TRUSTED_PACKAGE, ValueFormat.EVENT, StartMode.AFTER_FIRST_BACKFILL,
            NotificationKafkaConstants.CHAT_EVENTS_ENABLED);

    /** Kafka 레코드 값을 받는 형식 */
    public enum ValueFormat {
        /** {@code __TypeId__} 헤더로 신뢰 패키지의 이벤트 레코드로 바꾼다 */
        EVENT(Object.class),
        /** 바이트 그대로 받는다. 신뢰 타입 검사와 역직렬화는 리스너가 한다 */
        BYTES(byte[].class);

        private final Class<?> valueType;

        ValueFormat(Class<?> valueType) {
            this.valueType = valueType;
        }

        /** ConsumerFactory·ContainerFactory의 값 타입 */
        public Class<?> valueType() {
            return valueType;
        }
    }

    /** 리스너 컨테이너를 시작하는 때 */
    public enum StartMode {
        /** 앱 기동 때 */
        ON_STARTUP,
        /** 첫 회원 복제본 Backfill이 끝난 뒤({@code NotificationAlertListenerStarter}, ND-078) */
        AFTER_FIRST_BACKFILL
    }

    private final String beanNamePart;
    private final String topic;
    private final String groupId;
    private final String trustedPackage;
    private final ValueFormat valueFormat;
    private final StartMode startMode;
    private final String enabledProperty;

    NotificationKafkaConsumer(String beanNamePart, String topic, String groupId, String trustedPackage,
            ValueFormat valueFormat, StartMode startMode, String enabledProperty) {
        this.beanNamePart = beanNamePart;
        this.topic = topic;
        this.groupId = groupId;
        this.trustedPackage = trustedPackage;
        this.valueFormat = valueFormat;
        this.startMode = startMode;
        this.enabledProperty = enabledProperty;
    }

    public String topic() {
        return topic;
    }

    public String groupId() {
        return groupId;
    }

    /** 이벤트 타입의 신뢰 패키지. {@link ValueFormat#EVENT} 소비자의 역직렬화기가 쓴다(바이트로 받는 강의는 리스너가 같은 상수로 검사한다) */
    public String trustedPackage() {
        return trustedPackage;
    }

    public ValueFormat valueFormat() {
        return valueFormat;
    }

    public StartMode startMode() {
        return startMode;
    }

    /**
     * 이 소비자의 빈을 만들지. {@code @ConditionalOnBooleanProperty(name = …, matchIfMissing = true)}와 같은 판정이다: 속성이 없으면
     * 켜고, 있으면 값이 {@code true}(대소문자 무관)일 때만 켠다(Spring Boot {@code OnPropertyCondition}).
     */
    public boolean enabled(PropertyResolver properties) {
        if (!properties.containsProperty(enabledProperty)) {
            return true;
        }
        return "true".equalsIgnoreCase(properties.getProperty(enabledProperty));
    }

    /** 운영 소비자 값: 상수의 재시도·DLT 값을 쓴다 */
    public NotificationKafkaConsumerSettings settings() {
        return NotificationKafkaConsumerSettings.of(topic, groupId);
    }

    /** 소비자 값({@link NotificationKafkaConsumerSettings}). 리스너와 시험이 이 이름으로 받는다 */
    public String settingsBean() {
        return beanName("Settings");
    }

    public String consumerFactoryBean() {
        return beanName("ConsumerFactory");
    }

    public String deadLetterProducerFactoryBean() {
        return beanName("DeadLetterProducerFactory");
    }

    public String deadLetterTemplateBean() {
        return beanName("DeadLetterTemplate");
    }

    public String errorHandlerBean() {
        return beanName("ErrorHandler");
    }

    /** 리스너의 {@code containerFactory} */
    public String containerFactoryBean() {
        return beanName("ListenerContainerFactory");
    }

    public String deadLetterTopicBean() {
        return beanName("DeadLetterTopic");
    }

    private String beanName(String role) {
        return "notification" + beanNamePart + "Event" + role;
    }
}
