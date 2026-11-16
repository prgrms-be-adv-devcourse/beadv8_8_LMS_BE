package com.hapbang.auxiliary.notification.in.event.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.hapbang.auxiliary.notification.app.NotificationOutcomeRecorder;

/**
 * 등록기로 만든 Kafka 빈도 예전 {@code @Bean} 메서드처럼 빈 사이 의존 관계가 기록되는지 본다(N14 S6 리뷰, ND-184, test-spec NT-REF-06).
 * 의존 관계가 있어야 컨텍스트를 닫을 때 쓰는 쪽(템플릿·컨테이너 팩토리)이 쓰이는 쪽(팩토리·오류 처리기)보다 먼저 소멸된다.
 * <p>
 * {@code @Bean} 메서드 빈은 받은 인자 빈과 설정 클래스 빈에 의존한다고 기록됐다. 같은 관계를 단정한다.
 */
class NotificationKafkaConsumerDependencyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(NotificationOutcomeRecorder.class, NotificationKafkaMetrics.class,
                    NotificationKafkaConfig.class, NotificationKafkaErrorHandlerConfig.class,
                    NotificationKafkaTopicConfig.class)
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9", "spring.kafka.admin.auto-create=false");

    @ParameterizedTest
    @EnumSource(NotificationKafkaConsumer.class)
    void recordsDependenciesLikeBeanMethods(NotificationKafkaConsumer consumer) {
        runner.run(context -> {
            var beanFactory = context.getBeanFactory();
            // 모든 빈을 만든 뒤에 본다(의존 관계는 만들 때 기록된다)
            context.getBean(consumer.containerFactoryBean());
            context.getBean(consumer.deadLetterTopicBean());
            String config = context.getBeanNamesForType(NotificationKafkaConfig.class)[0];
            String errorHandlers = context.getBeanNamesForType(NotificationKafkaErrorHandlerConfig.class)[0];
            String topics = context.getBeanNamesForType(NotificationKafkaTopicConfig.class)[0];

            assertThat(beanFactory.getDependenciesForBean(consumer.consumerFactoryBean()))
                    .contains(config, consumer.settingsBean());
            assertThat(beanFactory.getDependenciesForBean(consumer.deadLetterProducerFactoryBean()))
                    .contains(config, consumer.settingsBean());
            assertThat(beanFactory.getDependenciesForBean(consumer.deadLetterTemplateBean()))
                    .contains(config, consumer.deadLetterProducerFactoryBean(), consumer.settingsBean());
            assertThat(beanFactory.getDependenciesForBean(consumer.errorHandlerBean()))
                    .contains(errorHandlers, consumer.deadLetterTemplateBean(), consumer.settingsBean());
            assertThat(beanFactory.getDependenciesForBean(consumer.containerFactoryBean()))
                    .contains(config, consumer.consumerFactoryBean(), consumer.errorHandlerBean());
            assertThat(beanFactory.getDependenciesForBean(consumer.deadLetterTopicBean()))
                    .contains(topics, consumer.settingsBean());
        });
    }
}
