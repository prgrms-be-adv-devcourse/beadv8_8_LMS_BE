package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 알림 리스너 여섯 개의 컨테이너 구성이 리팩토링 전후로 같은지 고정한다(N14, ND-181·ND-182, test-spec NT-REF-04).
 * <p>
 * 리팩토링 전 코드로 쓰고 GREEN을 확인했다. 리팩토링 뒤에도 고치지 않고 통과해야 한다. 컨테이너 ID·토픽·group·Bean 이름은 문자열로 적는다.
 */
@IntegrationTest
@Import(KafkaTestcontainersConfig.class)
class NotificationKafkaListenerConfigurationTest {

    /** 컨테이너 ID → {토픽, group, 오류 처리기 Bean 이름, 앱 기동 때 시작하는지} */
    private static final Map<String, Expected> LISTENERS = Map.of(
            "notification-member-events", new Expected("member.events.v1",
                    "auxiliary-app.notification-member-replica", "notificationMemberEventErrorHandler", true),
            "notification-appearance-events", new Expected("appearance.events.v1",
                    "auxiliary-app.notification-appearance-alert", "notificationAppearanceEventErrorHandler", false),
            "notification-billing-events", new Expected("billing.events.v1",
                    "auxiliary-app.notification-billing-alert", "notificationBillingEventErrorHandler", false),
            "notification-lecture-events", new Expected("lecture.events.v1",
                    "auxiliary-app.notification-lecture-alert", "notificationLectureEventErrorHandler", false),
            "notification-customersupport-events", new Expected("customersupport.events.v1",
                    "auxiliary-app.notification-customersupport-alert",
                    "notificationCustomerSupportEventErrorHandler", false),
            "notification-chat-events", new Expected("chat.events.v1", "auxiliary-app.notification-chat-alert",
                    "notificationChatEventErrorHandler", false));

    record Expected(String topic, String group, String errorHandler, boolean autoStartup) {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        for (String consumer : new String[] {"member", "appearance", "billing", "lecture", "customersupport", "chat"}) {
            registry.add("hapbang.notification." + consumer + "-events.enabled", () -> "true");
        }
    }

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private ApplicationContext context;

    @Test
    void registersSameListenerContainers() {
        assertThat(registry.getListenerContainerIds()).containsExactlyInAnyOrderElementsOf(LISTENERS.keySet());

        LISTENERS.forEach((id, expected) -> {
            MessageListenerContainer container = registry.getListenerContainer(id);
            assertThat(container).as(id).isInstanceOf(ConcurrentMessageListenerContainer.class);
            ConcurrentMessageListenerContainer<?, ?> concurrent = (ConcurrentMessageListenerContainer<?, ?>) container;
            ContainerProperties properties = concurrent.getContainerProperties();
            assertThat(properties.getTopics()).as(id).containsExactly(expected.topic());
            assertThat(concurrent.getGroupId()).as(id).isEqualTo(expected.group());
            assertThat(concurrent.getConcurrency()).as(id).isEqualTo(1);
            assertThat(concurrent.isAutoStartup()).as(id).isEqualTo(expected.autoStartup());
            assertThat(properties.getAckMode()).as(id).isEqualTo(ContainerProperties.AckMode.RECORD);
            assertThat(properties.isObservationEnabled()).as(id).isTrue();
            assertThat(concurrent.getCommonErrorHandler()).as(id).isSameAs(context.getBean(expected.errorHandler()));
        });
    }
}
