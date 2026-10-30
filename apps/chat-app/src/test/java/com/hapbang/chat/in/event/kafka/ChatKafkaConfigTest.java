package com.hapbang.chat.in.event.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.chat.app.ChatNotificationFacade;
import com.hapbang.chat.app.ChatUserFacade;
import com.hapbang.chat.in.event.AppearanceNotificationEventListener;
import com.hapbang.chat.in.event.ChatMemberEventTopicVerifier;
import com.hapbang.chat.in.event.ChatUserEventListener;
import com.hapbang.chat.in.websocket.ChatMessageSender;

/**
 * Kafka 소비 설정은 설정 클래스와 상수({@link ChatKafkaConstants})로만 정하고, 설정 키는 소비를 켜고 끄는 {@code enabled} 둘뿐이다.
 * 소비자는 설정이 없으면 켜지고 소비자마다 따로 끈다. 브로커에 연결하지 않도록 토픽 자동 생성은 끈다.
 */
class ChatKafkaConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(ChatKafkaConfig.class, ChatKafkaErrorHandlerConfig.class, ChatKafkaTopicConfig.class,
                    ChatMemberEventTopicVerifier.class)
            .withPropertyValues("spring.kafka.admin.auto-create=false");

    @Test
    void 이름과_값은_팀_Kafka_규칙을_따른다() {
        assertThat(ChatKafkaConstants.MEMBER_EVENTS_TOPIC).isEqualTo("member.events.v1");
        assertThat(ChatKafkaConstants.MEMBER_REPLICA_GROUP).isEqualTo("chat-app.member-replica");
        assertThat(ChatKafkaConstants.MEMBER_EVENTS_DLT).isEqualTo("member.events.v1.chat-app.member-replica.DLT");
        assertThat(ChatKafkaConstants.APPEARANCE_EVENTS_TOPIC).isEqualTo("appearance.events.v1");
        assertThat(ChatKafkaConstants.APPEARANCE_NOTIFICATION_GROUP).isEqualTo("chat-app.appearance-notification");
        assertThat(ChatKafkaConstants.APPEARANCE_EVENTS_DLT)
                .isEqualTo("appearance.events.v1.chat-app.appearance-notification.DLT");
        assertThat(ChatKafkaConstants.AUTO_OFFSET_RESET).isEqualTo("earliest");
        assertThat(ChatKafkaConstants.MAX_RETRIES).isEqualTo(3);
        assertThat(ChatKafkaConstants.RETRY_INITIAL_INTERVAL).isEqualTo(Duration.ofSeconds(1));
        assertThat(ChatKafkaConstants.RETRY_MULTIPLIER).isEqualTo(2.0);
        assertThat(ChatKafkaConstants.RETRY_MAX_INTERVAL).isEqualTo(Duration.ofSeconds(10));
        assertThat(ChatKafkaConstants.DEAD_LETTER_PARTITIONS).isEqualTo(3);
        assertThat(ChatKafkaConstants.DEAD_LETTER_REPLICAS).isEqualTo((short) 1);
        assertThat(ChatKafkaConstants.DEAD_LETTER_RETENTION.toMillis()).isEqualTo(604_800_000L);
    }

    @Test
    void 설정이_없으면_두_소비자의_빈을_상수_값으로_만든다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed()
                    .hasBean(ChatKafkaConfig.MEMBER_EVENT_CONTAINER_FACTORY)
                    .hasBean(ChatKafkaConfig.APPEARANCE_EVENT_CONTAINER_FACTORY)
                    .hasSingleBean(ChatMemberEventTopicVerifier.class);
            assertThat(settings(context, ChatKafkaConfig.MEMBER_EVENT_SETTINGS)).isEqualTo(new ChatKafkaConsumerSettings(
                    "member.events.v1", "chat-app.member-replica", "member.events.v1.chat-app.member-replica.DLT", 3,
                    Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(10), 3, (short) 1));
            assertThat(settings(context, ChatKafkaConfig.APPEARANCE_EVENT_SETTINGS)).isEqualTo(
                    new ChatKafkaConsumerSettings("appearance.events.v1", "chat-app.appearance-notification",
                            "appearance.events.v1.chat-app.appearance-notification.DLT", 3, Duration.ofSeconds(1),
                            Duration.ofSeconds(10), Duration.ofSeconds(10), 3, (short) 1));
            assertThat(context.getBean("chatMemberEventDeadLetterTopic", NewTopic.class)).satisfies(topic -> {
                assertThat(topic.name()).isEqualTo("member.events.v1.chat-app.member-replica.DLT");
                assertThat(topic.numPartitions()).isEqualTo(3);
                assertThat(topic.replicationFactor()).isEqualTo((short) 1);
                assertThat(topic.configs()).containsEntry("retention.ms", "604800000");
            });
            assertThat(context.getBean("chatAppearanceEventDeadLetterTopic", NewTopic.class)).satisfies(topic -> {
                assertThat(topic.name()).isEqualTo("appearance.events.v1.chat-app.appearance-notification.DLT");
                assertThat(topic.numPartitions()).isEqualTo(3);
                assertThat(topic.configs()).containsEntry("retention.ms", "604800000");
            });
        });
    }

    @Test
    void 소비와_DLT_발행_props는_코드에_있고_연결_주소만_설정에서_받는다() {
        runner.withPropertyValues("spring.kafka.bootstrap-servers=broker:19092").run(context -> {
            assertThat(consumerConfig(context, "chatMemberEventConsumerFactory"))
                    .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:19092")
                    .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "chat-app.member-replica")
                    .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                    .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            assertThat(consumerConfig(context, "chatAppearanceEventConsumerFactory"))
                    .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:19092")
                    .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "chat-app.appearance-notification");
            assertThat(context.getBean("chatMemberEventConsumerFactory", DefaultKafkaConsumerFactory.class)
                    .getValueDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);
            // DLT 발행 한 번 10초 = 메타데이터 대기 5초 + 전송 완료 대기 5초
            assertThat(context.getBean("chatMemberEventDeadLetterProducerFactory", ProducerFactory.class)
                    .getConfigurationProperties())
                    .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:19092")
                    .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                    .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000L)
                    .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000)
                    .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000)
                    .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 0);
        });
    }

    @Test
    void 연결_주소가_없으면_Spring_Boot_기본값과_같은_주소를_쓴다() {
        runner.run(context -> assertThat(consumerConfig(context, "chatMemberEventConsumerFactory"))
                .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"));
    }

    @Test
    void 리스너_컨테이너는_레코드마다_커밋하고_소비자별_오류_처리기를_쓴다() {
        runner.run(context -> {
            ConcurrentKafkaListenerContainerFactory<?, ?> factory = context.getBean(
                    ChatKafkaConfig.MEMBER_EVENT_CONTAINER_FACTORY, ConcurrentKafkaListenerContainerFactory.class);

            assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
            assertThat(factory.getConsumerFactory()).isSameAs(context.getBean("chatMemberEventConsumerFactory"));
            assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler"))
                    .isSameAs(context.getBean(ChatKafkaErrorHandlerConfig.MEMBER_EVENT_ERROR_HANDLER));
        });
    }

    @Test
    void 두_소비자를_함께_켜면_리스너가_각자의_토픽과_그룹과_컨테이너_팩토리를_쓴다() {
        // 리스너 컨테이너를 만들기만 하고 시작하지 않는다. spring.kafka.listener.auto-startup은 Boot의 기본 팩토리에만
        // 적용되므로 chat-app 팩토리에 직접 끈다. 로컬 Kafka가 떠 있어도 운영 토픽·그룹에 붙지 않도록 주소도 쓰지 않는 곳으로 둔다
        runner.withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:9")
                .withBean("noListenerAutoStartup", BeanPostProcessor.class, ChatKafkaConfigTest::noListenerAutoStartup)
                .withBean(ChatUserFacade.class, () -> mock(ChatUserFacade.class))
                .withBean(ChatNotificationFacade.class, () -> mock(ChatNotificationFacade.class))
                .withBean(ChatMessageSender.class, () -> mock(ChatMessageSender.class))
                .withUserConfiguration(ChatUserEventListener.class, AppearanceNotificationEventListener.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);

                    assertThat(registry.getListenerContainer("chat-member-events")).satisfies(container -> {
                        assertThat(container.getContainerProperties().getTopics()).containsExactly("member.events.v1");
                        assertThat(container.getGroupId()).isEqualTo("chat-app.member-replica");
                        assertThat(container.getContainerProperties().getAckMode())
                                .isEqualTo(ContainerProperties.AckMode.RECORD);
                        assertThat(container.isAutoStartup()).isFalse();
                        assertThat(container.isRunning()).isFalse();
                    });
                    assertThat(registry.getListenerContainer("chat-appearance-events")).satisfies(container -> {
                        assertThat(container.getContainerProperties().getTopics())
                                .containsExactly("appearance.events.v1");
                        assertThat(container.getGroupId()).isEqualTo("chat-app.appearance-notification");
                        assertThat(container.isAutoStartup()).isFalse();
                        assertThat(container.isRunning()).isFalse();
                    });
                });
    }

    @Test
    void DLT_템플릿은_발행_실패_로그에_값을_남기지_않게_만든다() {
        runner.run(context -> {
            for (String name : new String[] {ChatKafkaConfig.MEMBER_EVENT_DEAD_LETTER_TEMPLATE,
                    ChatKafkaConfig.APPEARANCE_EVENT_DEAD_LETTER_TEMPLATE}) {
                Object producerListener = ReflectionTestUtils.getField(context.getBean(name, KafkaTemplate.class),
                        "producerListener");

                assertThat(producerListener).isInstanceOf(LoggingProducerListener.class);
                assertThat(ReflectionTestUtils.getField(producerListener, "includeContents")).isEqualTo(false);
            }
        });
    }

    @Test
    void 회원_이벤트_소비를_끄면_그_소비자의_빈만_만들지_않고_Boot_기본_Kafka_빈은_그대로다() {
        runner.withPropertyValues(ChatKafkaConstants.MEMBER_EVENTS_ENABLED + "=false").run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(ChatKafkaConfig.MEMBER_EVENT_SETTINGS)
                    .doesNotHaveBean(ChatKafkaConfig.MEMBER_EVENT_CONTAINER_FACTORY)
                    .doesNotHaveBean(ChatKafkaErrorHandlerConfig.MEMBER_EVENT_ERROR_HANDLER)
                    .doesNotHaveBean("chatMemberEventDeadLetterTopic")
                    .doesNotHaveBean(ChatMemberEventTopicVerifier.class)
                    .hasBean(ChatKafkaConfig.APPEARANCE_EVENT_CONTAINER_FACTORY)
                    .hasBean("kafkaTemplate")
                    .hasBean("kafkaConsumerFactory")
                    .hasBean("kafkaProducerFactory");
            // 오류 처리기가 하나뿐이어도 기본 후보가 아니라 Boot의 기본 리스너 컨테이너 팩토리가 가져가지 않는다
            assertThat(ReflectionTestUtils.getField(context.getBean("kafkaListenerContainerFactory"),
                    "commonErrorHandler")).isNull();
        });
    }

    @Test
    void 출연_이벤트_소비를_끄면_그_소비자의_빈만_만들지_않는다() {
        runner.withPropertyValues(ChatKafkaConstants.APPEARANCE_EVENTS_ENABLED + "=false").run(context ->
                assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ChatKafkaConfig.APPEARANCE_EVENT_SETTINGS)
                        .doesNotHaveBean(ChatKafkaConfig.APPEARANCE_EVENT_CONTAINER_FACTORY)
                        .doesNotHaveBean(ChatKafkaErrorHandlerConfig.APPEARANCE_EVENT_ERROR_HANDLER)
                        .doesNotHaveBean("chatAppearanceEventDeadLetterTopic")
                        .hasBean(ChatKafkaConfig.MEMBER_EVENT_CONTAINER_FACTORY)
                        .hasSingleBean(ChatMemberEventTopicVerifier.class));
    }

    /** 모든 리스너 컨테이너 팩토리가 컨테이너를 자동으로 시작하지 않게 한다(시험 전용) */
    private static BeanPostProcessor noListenerAutoStartup() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
                    factory.setAutoStartup(false);
                }
                return bean;
            }
        };
    }

    private static ChatKafkaConsumerSettings settings(AssertableApplicationContext context, String name) {
        return context.getBean(name, ChatKafkaConsumerSettings.class);
    }

    private static Map<String, Object> consumerConfig(AssertableApplicationContext context, String name) {
        return context.getBean(name, ConsumerFactory.class).getConfigurationProperties();
    }
}
