package com.hapbang.chat.in.event;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 회원 이벤트 소비 설정의 기본값은 코드에 있고(application.yaml에 두지 않는다), yaml·환경 변수가 같은 키로 덮는다.
 * 소비는 설정이 없으면 켜지고 {@code enabled=false}로 끈다. 브로커에 연결하지 않도록 토픽 자동 생성은 끈다.
 */
class ChatMemberEventSettingsTest {

    private static final String PREFIX = "hapbang.chat.member-events";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(ChatMemberEventKafkaConfig.class, ChatMemberEventTopicVerifier.class)
            .withPropertyValues("spring.kafka.admin.auto-create=false");

    @Test
    void 아무것도_설정하지_않으면_코드_기본값을_쓴다() {
        ChatMemberEventProperties properties = bind(Map.of());

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.topic()).isEqualTo("member.events.v1");
        assertThat(properties.groupId()).isEqualTo("chat-app.member-replica");
        assertThat(properties.deadLetterTopic()).isEqualTo("member.events.v1.chat-app.member-replica.DLT");
        assertThat(properties.maxRetries()).isEqualTo(3L);
        assertThat(properties.retryInitialInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.retryMaxInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.deadLetterSendTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.autoOffsetReset()).isEqualTo("earliest");
        assertThat(properties.deadLetterPartitions()).isEqualTo(3);
        assertThat(properties.deadLetterReplicas()).isEqualTo((short) 1);
    }

    @Test
    void yaml_키로_설정하면_코드_기본값을_덮는다() {
        ChatMemberEventProperties properties = bind(Map.of(PREFIX + ".group-id", "custom-group",
                PREFIX + ".max-retries", "7", PREFIX + ".enabled", "false"));

        assertThat(properties.deadLetterTopic()).isEqualTo("member.events.v1.custom-group.DLT");
        assertThat(properties.maxRetries()).isEqualTo(7L);
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.topic()).isEqualTo("member.events.v1");
    }

    @Test
    void 환경_변수로_설정하면_코드_기본값을_덮는다() {
        Binder binder = new Binder(ConfigurationPropertySources.from(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("HAPBANG_CHAT_MEMBEREVENTS_GROUPID", "env-group",
                        "HAPBANG_CHAT_MEMBEREVENTS_MAXRETRIES", "5"))));

        ChatMemberEventProperties properties = binder.bindOrCreate(PREFIX, ChatMemberEventProperties.class);

        assertThat(properties.deadLetterTopic()).isEqualTo("member.events.v1.env-group.DLT");
        assertThat(properties.maxRetries()).isEqualTo(5L);
    }

    @Test
    void 설정이_없으면_소비_빈을_만들고_기본값으로_DLT와_발행_시간_상한을_정한다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed()
                    .hasBean(ChatMemberEventKafkaConfig.CONTAINER_FACTORY)
                    .hasSingleBean(ChatMemberEventTopicVerifier.class);
            assertThat(context.getBean("chatMemberEventDeadLetterTopic", NewTopic.class))
                    .satisfies(topic -> {
                        assertThat(topic.name()).isEqualTo("member.events.v1.chat-app.member-replica.DLT");
                        assertThat(topic.numPartitions()).isEqualTo(3);
                        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
                        assertThat(topic.configs()).containsEntry("retention.ms", "604800000");
                    });
            assertThat(deadLetterProducerConfig(context.getBean("chatMemberEventDeadLetterProducerFactory",
                    ProducerFactory.class)))
                    .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000L)
                    .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        });
    }

    @Test
    void 설정한_값이_빈에_반영된다() {
        runner.withPropertyValues(PREFIX + ".group-id=custom-group", PREFIX + ".dead-letter-partitions=1",
                PREFIX + ".dead-letter-send-timeout=4s").run(context -> {
                    assertThat(context.getBean("chatMemberEventDeadLetterTopic", NewTopic.class))
                            .satisfies(topic -> {
                                assertThat(topic.name()).isEqualTo("member.events.v1.custom-group.DLT");
                                assertThat(topic.numPartitions()).isEqualTo(1);
                            });
                    assertThat(deadLetterProducerConfig(context.getBean("chatMemberEventDeadLetterProducerFactory",
                            ProducerFactory.class)))
                            .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000L)
                            .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2_000);
                });
    }

    @Test
    void DLT_템플릿은_발행_실패_로그에_값을_남기지_않게_만든다() {
        runner.run(context -> {
            KafkaTemplate<?, ?> template = context.getBean("chatMemberEventDeadLetterTemplate", KafkaTemplate.class);
            Object producerListener = ReflectionTestUtils.getField(template, "producerListener");

            assertThat(producerListener).isInstanceOf(LoggingProducerListener.class);
            assertThat(ReflectionTestUtils.getField(producerListener, "includeContents")).isEqualTo(false);
        });
    }

    @Test
    void enabled가_false면_소비_빈을_만들지_않는다() {
        runner.withPropertyValues(PREFIX + ".enabled=false").run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(ChatMemberEventKafkaConfig.CONTAINER_FACTORY)
                .doesNotHaveBean("chatMemberEventDeadLetterTopic")
                .doesNotHaveBean(ChatMemberEventTopicVerifier.class));
    }

    private static ChatMemberEventProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate(PREFIX,
                ChatMemberEventProperties.class);
    }

    private static Map<String, Object> deadLetterProducerConfig(ProducerFactory<?, ?> producerFactory) {
        return producerFactory.getConfigurationProperties();
    }
}
