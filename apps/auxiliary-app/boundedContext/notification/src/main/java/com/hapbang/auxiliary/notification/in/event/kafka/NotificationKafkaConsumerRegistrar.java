package com.hapbang.auxiliary.notification.in.event.kafka;

import java.util.Arrays;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * 알림 Kafka 소비자({@link NotificationKafkaConsumer}) 마다 빈 일곱 개를 리팩토링 전 {@code @Bean} 메서드와 같은 이름·속성으로 등록한다
 * (N14, ND-180).
 * <ul>
 *     <li>소비자 값(settings)과 DLT 토픽 선언은 기본 후보로, 나머지 Kafka 빈 다섯 개는 기본 후보에서 빼고
 *     ({@code defaultCandidate=false}) 이름 주입은 그대로 받는다({@code autowireCandidate=true}). 그래서 Boot의 기본 Kafka 빈이
 *     물러나지 않는다. Spring 7 {@code BeanRegistrar}는 기본 후보만 빼는 설정이 없어 이 방식을 쓴다.</li>
 *     <li>활성 속성은 {@link NotificationKafkaConsumer#enabled}가 {@code @ConditionalOnBooleanProperty}와 같게 판정한다.</li>
 *     <li>빈은 만들 때 다른 빈을 이름으로 찾는다. 시험이 소비자 값을 이름으로 바꾸면({@code @TestBean}) 그 값으로 만든다. supplier 안에서
 *     찾은 빈은 만드는 빈의 의존 관계로 기록돼, 예전 {@code @Bean} 메서드처럼 쓰는 쪽이 먼저 소멸된다(ND-184).</li>
 *     <li>예전과 다른 점(ND-184): 조건 평가 보고서(actuator {@code /conditions})에 활성 속성 항목이 없고 {@code /beans}의 출처 정보가
 *     다르다. 오류 처리기·DLT 토픽도 {@link NotificationKafkaConfig}의 {@code @Import}로 등록되므로, 이 설정만 가져오는 문맥에서는 예전과
 *     결과가 다르다(지금 그런 문맥은 없다).</li>
 *     <li>등록 순서는 예전 설정 클래스 순서(소비자별 값·팩토리·템플릿·컨테이너 팩토리 → 오류 처리기 → DLT 토픽)와 같다. 소멸 메서드는
 *     {@code @Bean}처럼 추론한다.</li>
 * </ul>
 * auxiliary-app은 AOT·Native Image를 쓰지 않는다. 이 등록기의 instance supplier는 AOT 처리 때 코드로 생성되지 않으므로, AOT를 켜면
 * 따로 대응해야 한다(ND-180).
 */
class NotificationKafkaConsumerRegistrar implements ImportBeanDefinitionRegistrar {

    private final Environment environment;
    private final BeanFactory beanFactory;

    NotificationKafkaConsumerRegistrar(Environment environment, BeanFactory beanFactory) {
        this.environment = environment;
        this.beanFactory = beanFactory;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        var enabled = Arrays.stream(NotificationKafkaConsumer.values())
                .filter(consumer -> consumer.enabled(environment))
                .toList();
        enabled.forEach(consumer -> registerConsumer(registry, consumer));
        enabled.forEach(consumer -> register(registry, consumer.errorHandlerBean(),
                ResolvableType.forClass(DefaultErrorHandler.class), false,
                () -> errorHandlers().errorHandler(bean(consumer.deadLetterTemplateBean(), KafkaTemplate.class),
                        settings(consumer))));
        enabled.forEach(consumer -> register(registry, consumer.deadLetterTopicBean(),
                ResolvableType.forClass(NewTopic.class), true, () -> deadLetterTopic(consumer)));
    }

    private void registerConsumer(BeanDefinitionRegistry registry, NotificationKafkaConsumer consumer) {
        Class<?> valueType = consumer.valueFormat().valueType();
        register(registry, consumer.settingsBean(), ResolvableType.forClass(NotificationKafkaConsumerSettings.class),
                true, consumer::settings);
        register(registry, consumer.consumerFactoryBean(),
                ResolvableType.forClassWithGenerics(ConsumerFactory.class, String.class, valueType), false,
                () -> config().consumerFactory(consumer, settings(consumer)));
        register(registry, consumer.deadLetterProducerFactoryBean(),
                ResolvableType.forClassWithGenerics(ProducerFactory.class, String.class, Object.class), false,
                () -> config().deadLetterProducerFactory(settings(consumer)));
        register(registry, consumer.deadLetterTemplateBean(),
                ResolvableType.forClassWithGenerics(KafkaTemplate.class, String.class, Object.class), false,
                () -> config().deadLetterTemplate(bean(consumer.deadLetterProducerFactoryBean(), ProducerFactory.class),
                        settings(consumer)));
        register(registry, consumer.containerFactoryBean(),
                ResolvableType.forClassWithGenerics(ConcurrentKafkaListenerContainerFactory.class, String.class,
                        valueType), false,
                () -> config().listenerContainerFactory(consumer, bean(consumer.consumerFactoryBean(),
                        ConsumerFactory.class), bean(consumer.errorHandlerBean(), DefaultErrorHandler.class)));
    }

    private static void register(BeanDefinitionRegistry registry, String name, ResolvableType type,
            boolean defaultCandidate, Supplier<?> supplier) {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setTargetType(type);
        definition.setInstanceSupplier(supplier);
        definition.setDefaultCandidate(defaultCandidate);
        definition.setDestroyMethodName(AbstractBeanDefinition.INFER_METHOD);
        registry.registerBeanDefinition(name, definition);
    }

    private NotificationKafkaConfig config() {
        return beanFactory.getBean(NotificationKafkaConfig.class);
    }

    private NotificationKafkaErrorHandlerConfig errorHandlers() {
        return beanFactory.getBean(NotificationKafkaErrorHandlerConfig.class);
    }

    /**
     * DLT 토픽 선언. 예전 {@code @Bean} 메서드처럼 {@link NotificationKafkaTopicConfig} 빈에 의존한다고 기록되도록 그 빈을 먼저 찾는다
     * (supplier 안의 {@code getBean}은 만드는 빈의 의존 관계로 남는다, ND-184)
     */
    private NewTopic deadLetterTopic(NotificationKafkaConsumer consumer) {
        beanFactory.getBean(NotificationKafkaTopicConfig.class);
        return NotificationKafkaTopicConfig.deadLetterTopic(settings(consumer));
    }

    private NotificationKafkaConsumerSettings settings(NotificationKafkaConsumer consumer) {
        return bean(consumer.settingsBean(), NotificationKafkaConsumerSettings.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T bean(String name, Class<?> type) {
        return (T) beanFactory.getBean(name, type);
    }
}
