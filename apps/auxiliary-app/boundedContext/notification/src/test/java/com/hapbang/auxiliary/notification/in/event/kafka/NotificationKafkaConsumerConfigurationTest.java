package com.hapbang.auxiliary.notification.in.event.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CompositeRecordInterceptor;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.CompositeProducerListener;
import org.springframework.kafka.support.ExceptionMatcher;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.auxiliary.notification.app.NotificationOutcomeRecorder;

/**
 * 알림 Kafka 소비자 여섯 개의 빈 구성이 리팩토링 전후로 같은지 고정한다(N14, ND-182, test-spec NT-REF-01~03).
 * <p>
 * 이 시험은 리팩토링 전 코드로 쓰고 GREEN을 확인했다. 리팩토링 뒤에도 고치거나 조건을 완화하지 않고 통과해야 한다. 그래서 Bean 이름·토픽·
 * group·신뢰 패키지는 상수를 쓰지 않고 문자열로 적는다(상수를 옮기거나 바꾸면 여기서 드러난다).
 */
class NotificationKafkaConsumerConfigurationTest {

    private static final String BOOTSTRAP = "localhost:9";

    /** 소비자 여섯 개의 기대값. Bean 이름은 {@code notification<이름>Event...} 규칙이다 */
    enum Consumer {
        MEMBER("Member", "member.events.v1", "auxiliary-app.notification-member-replica",
                "com.hapbang.shared.member.event", true, "hapbang.notification.member-events.enabled"),
        APPEARANCE("Appearance", "appearance.events.v1", "auxiliary-app.notification-appearance-alert",
                "com.hapbang.shared.appearance.event", false, "hapbang.notification.appearance-events.enabled"),
        BILLING("Billing", "billing.events.v1", "auxiliary-app.notification-billing-alert",
                "com.hapbang.shared.billing.event", false, "hapbang.notification.billing-events.enabled"),
        /** 값을 바이트로 받는다(신뢰 패키지 검사는 리스너, ND-096) */
        LECTURE("Lecture", "lecture.events.v1", "auxiliary-app.notification-lecture-alert", null, false,
                "hapbang.notification.lecture-events.enabled"),
        CUSTOMERSUPPORT("CustomerSupport", "customersupport.events.v1",
                "auxiliary-app.notification-customersupport-alert", "com.hapbang.shared.customersupport.event", false,
                "hapbang.notification.customersupport-events.enabled"),
        CHAT("Chat", "chat.events.v1", "auxiliary-app.notification-chat-alert", "com.hapbang.shared.chat.event", false,
                "hapbang.notification.chat-events.enabled");

        final String name;
        final String topic;
        final String group;
        /** null이면 바이트 값 */
        final String trustedPackage;
        /** 앱 기동 때 바로 시작하는지(회원 복제본만). 나머지는 첫 Backfill 뒤에 시작한다(ND-078) */
        final boolean startsWithContext;
        final String enabledProperty;

        Consumer(String name, String topic, String group, String trustedPackage, boolean startsWithContext,
                String enabledProperty) {
            this.name = name;
            this.topic = topic;
            this.group = group;
            this.trustedPackage = trustedPackage;
            this.startsWithContext = startsWithContext;
            this.enabledProperty = enabledProperty;
        }

        String deadLetterTopic() {
            return topic + "." + group + ".DLT";
        }

        String settings() {
            return "notification" + name + "EventSettings";
        }

        String consumerFactory() {
            return "notification" + name + "EventConsumerFactory";
        }

        String deadLetterProducerFactory() {
            return "notification" + name + "EventDeadLetterProducerFactory";
        }

        String deadLetterTemplate() {
            return "notification" + name + "EventDeadLetterTemplate";
        }

        String errorHandler() {
            return "notification" + name + "EventErrorHandler";
        }

        String containerFactory() {
            return "notification" + name + "EventListenerContainerFactory";
        }

        String deadLetterTopicDeclaration() {
            return "notification" + name + "EventDeadLetterTopic";
        }

        /** 기본 후보에서 빠진 Kafka 빈(이름으로만 쓴다) */
        List<String> kafkaBeans() {
            return List.of(consumerFactory(), deadLetterProducerFactory(), deadLetterTemplate(), errorHandler(),
                    containerFactory());
        }

        List<String> allBeans() {
            List<String> names = new ArrayList<>(kafkaBeans());
            names.add(settings());
            names.add(deadLetterTopicDeclaration());
            return names;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(NotificationOutcomeRecorder.class, NotificationKafkaMetrics.class,
                    NotificationKafkaConfig.class, NotificationKafkaErrorHandlerConfig.class,
                    NotificationKafkaTopicConfig.class)
            // broker가 없는 문맥이다. Boot KafkaAdmin이 기동 때 DLT 토픽을 만들려고 기다리지 않게 끈다(NewTopic 빈은 그대로 만든다)
            .withPropertyValues("spring.kafka.bootstrap-servers=" + BOOTSTRAP, "spring.kafka.admin.auto-create=false");

    // NT-REF-01 빈 등록

    @ParameterizedTest
    @EnumSource(Consumer.class)
    void registersBeansWithSameNamesAndCandidateFlags(Consumer consumer) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(consumer.settings())).isInstanceOf(NotificationKafkaConsumerSettings.class);
            assertThat(context.getBean(consumer.consumerFactory())).isInstanceOf(DefaultKafkaConsumerFactory.class);
            assertThat(context.getBean(consumer.deadLetterProducerFactory()))
                    .isInstanceOf(DefaultKafkaProducerFactory.class);
            assertThat(context.getBean(consumer.deadLetterTemplate())).isExactlyInstanceOf(KafkaTemplate.class);
            assertThat(context.getBean(consumer.errorHandler())).isExactlyInstanceOf(DefaultErrorHandler.class);
            assertThat(context.getBean(consumer.containerFactory()))
                    .isExactlyInstanceOf(ConcurrentKafkaListenerContainerFactory.class);
            assertThat(context.getBean(consumer.deadLetterTopicDeclaration())).isInstanceOf(NewTopic.class);

            for (String name : consumer.kafkaBeans()) {
                AbstractBeanDefinition definition = (AbstractBeanDefinition) context.getBeanFactory()
                        .getBeanDefinition(name);
                assertThat(definition.isDefaultCandidate()).as("%s defaultCandidate", name).isFalse();
                assertThat(definition.isAutowireCandidate()).as("%s autowireCandidate", name).isTrue();
            }
            for (String name : List.of(consumer.settings(), consumer.deadLetterTopicDeclaration())) {
                AbstractBeanDefinition definition = (AbstractBeanDefinition) context.getBeanFactory()
                        .getBeanDefinition(name);
                assertThat(definition.isDefaultCandidate()).as("%s defaultCandidate", name).isTrue();
                assertThat(definition.isAutowireCandidate()).as("%s autowireCandidate", name).isTrue();
            }
        });
    }

    /** 이름으로 하는 주입은 되고, 이름 없는 주입은 Boot 기본 Kafka 빈을 받는다 */
    @Test
    void injectsByQualifierAndLeavesBootDefaultsForUnqualifiedInjection() {
        runner.withUserConfiguration(InjectionClient.class).run(context -> {
            assertThat(context).hasNotFailed();
            InjectionClient client = context.getBean(InjectionClient.class);
            assertThat(client.billingConsumerFactory)
                    .isSameAs(context.getBean("notificationBillingEventConsumerFactory"));
            assertThat(client.billingDeadLetterTemplate)
                    .isSameAs(context.getBean("notificationBillingEventDeadLetterTemplate"));
            assertThat(client.billingErrorHandler).isSameAs(context.getBean("notificationBillingEventErrorHandler"));
            assertThat(client.billingContainerFactory)
                    .isSameAs(context.getBean("notificationBillingEventListenerContainerFactory"));
            assertThat(client.billingSettings).isSameAs(context.getBean("notificationBillingEventSettings"));

            assertThat(client.defaultConsumerFactory).isSameAs(context.getBean("kafkaConsumerFactory"));
            assertThat(client.defaultProducerFactory).isSameAs(context.getBean("kafkaProducerFactory"));
            assertThat(client.defaultTemplate).isSameAs(context.getBean("kafkaTemplate"));
            assertThat(context).hasBean("kafkaListenerContainerFactory");
        });
    }

    // NT-REF-02 구성 값

    @ParameterizedTest
    @EnumSource(Consumer.class)
    void keepsSettings(Consumer consumer) {
        runner.run(context -> assertThat(context.getBean(consumer.settings()))
                .isEqualTo(new NotificationKafkaConsumerSettings(consumer.topic, consumer.group,
                        consumer.deadLetterTopic(), 3, Duration.ofSeconds(1), Duration.ofSeconds(10),
                        Duration.ofSeconds(10), 3, (short) 1)));
    }

    @ParameterizedTest
    @EnumSource(Consumer.class)
    void keepsConsumerFactory(Consumer consumer) {
        runner.run(context -> {
            DefaultKafkaConsumerFactory<?, ?> factory = (DefaultKafkaConsumerFactory<?, ?>) context
                    .getBean(consumer.consumerFactory());
            assertThat(factory.getConfigurationProperties()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "bootstrap.servers", BOOTSTRAP,
                    "group.id", consumer.group,
                    "auto.offset.reset", "earliest",
                    "enable.auto.commit", false,
                    "allow.auto.create.topics", false));
            assertThat(factory.getKeyDeserializer()).isExactlyInstanceOf(StringDeserializer.class);
            if (consumer.trustedPackage == null) {
                assertThat(factory.getValueDeserializer()).isExactlyInstanceOf(ByteArrayDeserializer.class);
                return;
            }
            assertThat(factory.getValueDeserializer()).isExactlyInstanceOf(ErrorHandlingDeserializer.class);
            Object trusted = ReflectionTestUtils.getField(factory.getValueDeserializer(), "delegate");
            assertThat(trusted).isExactlyInstanceOf(TrustedEventDeserializer.class);
            assertThat(ReflectionTestUtils.getField(trusted, "trustedPackage")).isEqualTo(consumer.trustedPackage);
            Object json = ReflectionTestUtils.getField(trusted, "delegate");
            assertThat(json).isExactlyInstanceOf(JacksonJsonDeserializer.class);
            assertThat(ReflectionTestUtils.getField(json, "removeTypeHeaders")).isEqualTo(false);
        });
    }

    @ParameterizedTest
    @EnumSource(Consumer.class)
    void keepsDeadLetterProducer(Consumer consumer) {
        runner.run(context -> {
            DefaultKafkaProducerFactory<?, ?> factory = (DefaultKafkaProducerFactory<?, ?>) context
                    .getBean(consumer.deadLetterProducerFactory());
            assertThat(factory.getConfigurationProperties()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "bootstrap.servers", BOOTSTRAP,
                    "acks", "all",
                    "max.block.ms", 5_000L,
                    "delivery.timeout.ms", 5_000,
                    "request.timeout.ms", 5_000,
                    "linger.ms", 0));
            assertThat(factory.getKeySerializer()).isExactlyInstanceOf(StringSerializer.class);
            assertThat(factory.getValueSerializer()).isExactlyInstanceOf(DelegatingByTypeSerializer.class);
            @SuppressWarnings("unchecked")
            Map<Class<?>, Object> delegates = (Map<Class<?>, Object>) ReflectionTestUtils
                    .getField(factory.getValueSerializer(), "delegates");
            assertThat(delegates.keySet()).containsExactly(byte[].class, Object.class);
            assertThat(delegates.get(byte[].class)).isExactlyInstanceOf(ByteArraySerializer.class);
            assertThat(delegates.get(Object.class)).isExactlyInstanceOf(JacksonJsonSerializer.class);
            assertThat(ReflectionTestUtils.getField(factory.getValueSerializer(), "assignable")).isEqualTo(true);

            KafkaTemplate<?, ?> template = (KafkaTemplate<?, ?>) context.getBean(consumer.deadLetterTemplate());
            assertThat(template.getProducerFactory()).isSameAs(factory);
            assertThat(ReflectionTestUtils.getField(template, "observationEnabled")).isEqualTo(true);
            Object producerListener = ReflectionTestUtils.getField(template, "producerListener");
            assertThat(producerListener).isExactlyInstanceOf(CompositeProducerListener.class);
            @SuppressWarnings("unchecked")
            Collection<Object> listeners = (Collection<Object>) ReflectionTestUtils
                    .getField(producerListener, "delegates");
            assertThat(listeners).hasSize(2);
            Object logging = listeners.iterator().next();
            assertThat(logging).isExactlyInstanceOf(LoggingProducerListener.class);
            assertThat(ReflectionTestUtils.getField(logging, "includeContents")).isEqualTo(false);
            assertThat(definedIn(new ArrayList<>(listeners).get(1))).isEqualTo(NotificationKafkaMetrics.class);
        });
    }

    @ParameterizedTest
    @EnumSource(Consumer.class)
    void keepsErrorHandler(Consumer consumer) {
        runner.run(context -> {
            DefaultErrorHandler errorHandler = (DefaultErrorHandler) context.getBean(consumer.errorHandler());
            Object tracker = ReflectionTestUtils.getField(errorHandler, "failureTracker");
            ExponentialBackOffWithMaxRetries backOff = (ExponentialBackOffWithMaxRetries) ReflectionTestUtils
                    .getField(tracker, "backOff");
            assertThat(backOff.getMaxRetries()).isEqualTo(3);
            assertThat(backOff.getInitialInterval()).isEqualTo(1_000L);
            assertThat(backOff.getMultiplier()).isEqualTo(2.0);
            assertThat(backOff.getMaxInterval()).isEqualTo(10_000L);

            @SuppressWarnings("unchecked")
            List<Object> retryListeners = (List<Object>) ReflectionTestUtils.getField(tracker, "retryListeners");
            assertThat(retryListeners).hasSize(1);
            assertThat(definedIn(retryListeners.getFirst())).isEqualTo(NotificationKafkaMetrics.class);

            ExceptionMatcher matcher = ReflectionTestUtils.invokeMethod(errorHandler, "getExceptionMatcher");
            assertThat(matcher.match(new RejectedEventException("거절", null))).as("검증 실패는 재시도 안 함").isFalse();
            assertThat(matcher.match(new DeserializationException("본문", new byte[0], false, null)))
                    .as("역직렬화 실패는 재시도 안 함").isFalse();
            assertThat(matcher.match(new IllegalStateException("DB"))).as("그 밖의 실패는 재시도").isTrue();

            DeadLetterPublishingRecoverer recoverer = (DeadLetterPublishingRecoverer) ReflectionTestUtils
                    .getField(tracker, "recoverer");
            assertThat(ReflectionTestUtils.getField(recoverer, "failIfSendResultIsError")).isEqualTo(true);
            assertThat(ReflectionTestUtils.getField(recoverer, "timeoutBuffer")).isEqualTo(0L);
            assertThat(ReflectionTestUtils.getField(recoverer, "waitForSendResultTimeout"))
                    .isEqualTo(Duration.ofMillis(5_000));
            @SuppressWarnings("unchecked")
            BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination =
                    (BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>) ReflectionTestUtils
                            .getField(recoverer, "destinationResolver");
            assertThat(destination.apply(new ConsumerRecord<>(consumer.topic, 2, 7L, "k", "v"),
                    new IllegalStateException())).isEqualTo(new TopicPartition(consumer.deadLetterTopic(), 2));
            @SuppressWarnings("unchecked")
            Function<Object, Object> templates = (Function<Object, Object>) ReflectionTestUtils
                    .getField(recoverer, "templateResolver");
            assertThat(templates.apply(null)).isSameAs(context.getBean(consumer.deadLetterTemplate()));
        });
    }

    @ParameterizedTest
    @EnumSource(Consumer.class)
    void keepsContainerFactory(Consumer consumer) {
        runner.run(context -> {
            ConcurrentKafkaListenerContainerFactory<?, ?> factory = (ConcurrentKafkaListenerContainerFactory<?, ?>)
                    context.getBean(consumer.containerFactory());
            assertThat(factory.getConsumerFactory()).isSameAs(context.getBean(consumer.consumerFactory()));
            assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler"))
                    .isSameAs(context.getBean(consumer.errorHandler()));
            assertThat(ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(1);
            assertThat(ReflectionTestUtils.getField(factory, "autoStartup"))
                    .as("회원은 설정하지 않음(기본 시작), 나머지는 false")
                    .isEqualTo(consumer.startsWithContext ? null : Boolean.FALSE);
            ContainerProperties properties = factory.getContainerProperties();
            assertThat(properties.getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
            assertThat(properties.isObservationEnabled()).isTrue();

            assertThat(factory.getRecordInterceptor()).isExactlyInstanceOf(CompositeRecordInterceptor.class);
            @SuppressWarnings("unchecked")
            Collection<Object> interceptors = (Collection<Object>) ReflectionTestUtils
                    .getField(factory.getRecordInterceptor(), "delegates");
            List<Object> ordered = new ArrayList<>(interceptors);
            assertThat(ordered).hasSize(2);
            assertThat(ordered.get(0)).isExactlyInstanceOf(EventLogContextRecordInterceptor.class);
            assertThat(definedIn(ordered.get(1))).isEqualTo(NotificationKafkaMetrics.class);
        });
    }

    @ParameterizedTest
    @EnumSource(Consumer.class)
    void keepsDeadLetterTopicDeclaration(Consumer consumer) {
        runner.run(context -> {
            NewTopic topic = (NewTopic) context.getBean(consumer.deadLetterTopicDeclaration());
            assertThat(topic.name()).isEqualTo(consumer.deadLetterTopic());
            assertThat(topic.numPartitions()).isEqualTo(3);
            assertThat(topic.replicationFactor()).isEqualTo((short) 1);
            assertThat(topic.configs()).containsExactlyEntriesOf(Map.of("retention.ms", "604800000"));
        });
    }

    // NT-REF-03 활성 속성

    @ParameterizedTest
    @EnumSource(Consumer.class)
    void createsBeansWhenPropertyMissingOrTrue(Consumer consumer) {
        runner.run(context -> assertAllPresent(context, consumer));
        runner.withPropertyValues(consumer.enabledProperty + "=true").run(context -> assertAllPresent(context, consumer));
        runner.withPropertyValues(consumer.enabledProperty + "=TRUE").run(context -> assertAllPresent(context, consumer));
    }

    @ParameterizedTest
    @EnumSource(Consumer.class)
    void createsNoBeansWhenPropertyFalseAndLeavesOthers(Consumer consumer) {
        runner.withPropertyValues(consumer.enabledProperty + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            for (String name : consumer.allBeans()) {
                assertThat(context).as(name).doesNotHaveBean(name);
            }
            for (Consumer other : Consumer.values()) {
                if (other != consumer) {
                    assertAllPresent(context, other);
                }
            }
        });
    }

    /** 객체를 만든 클래스. 익명 클래스와 lambda 모두 그것을 정의한 클래스가 나온다 */
    private static Class<?> definedIn(Object object) {
        return object.getClass().getNestHost();
    }

    private static void assertAllPresent(AssertableApplicationContext context, Consumer consumer) {
        assertThat(context).hasNotFailed();
        for (String name : consumer.allBeans()) {
            assertThat(context).as(name).hasBean(name);
        }
    }

    /** 다른 클래스가 Kafka 빈을 받는 두 방식(이름 지정, 이름 없음) */
    static class InjectionClient {

        final ConsumerFactory<?, ?> billingConsumerFactory;
        final KafkaTemplate<?, ?> billingDeadLetterTemplate;
        final DefaultErrorHandler billingErrorHandler;
        final ConcurrentKafkaListenerContainerFactory<?, ?> billingContainerFactory;
        final NotificationKafkaConsumerSettings billingSettings;
        final ConsumerFactory<?, ?> defaultConsumerFactory;
        final ProducerFactory<?, ?> defaultProducerFactory;
        final KafkaTemplate<?, ?> defaultTemplate;

        InjectionClient(
                @Qualifier("notificationBillingEventConsumerFactory") ConsumerFactory<?, ?> billingConsumerFactory,
                @Qualifier("notificationBillingEventDeadLetterTemplate") KafkaTemplate<?, ?> billingDeadLetterTemplate,
                @Qualifier("notificationBillingEventErrorHandler") DefaultErrorHandler billingErrorHandler,
                @Qualifier("notificationBillingEventListenerContainerFactory")
                ConcurrentKafkaListenerContainerFactory<?, ?> billingContainerFactory,
                @Qualifier("notificationBillingEventSettings") NotificationKafkaConsumerSettings billingSettings,
                ConsumerFactory<?, ?> defaultConsumerFactory,
                ProducerFactory<?, ?> defaultProducerFactory,
                KafkaTemplate<?, ?> defaultTemplate) {
            this.billingConsumerFactory = billingConsumerFactory;
            this.billingDeadLetterTemplate = billingDeadLetterTemplate;
            this.billingErrorHandler = billingErrorHandler;
            this.billingContainerFactory = billingContainerFactory;
            this.billingSettings = billingSettings;
            this.defaultConsumerFactory = defaultConsumerFactory;
            this.defaultProducerFactory = defaultProducerFactory;
            this.defaultTemplate = defaultTemplate;
        }
    }
}
