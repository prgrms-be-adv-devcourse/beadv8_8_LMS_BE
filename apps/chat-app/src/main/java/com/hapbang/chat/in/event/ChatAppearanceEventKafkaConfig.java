package com.hapbang.chat.in.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import com.hapbang.chat.in.event.kafka.ChatDeadLetters;
import com.hapbang.chat.in.event.kafka.ChatKafkaListenerFactories;
import com.hapbang.chat.in.event.kafka.RejectedEventException;
import com.hapbang.chat.in.event.kafka.TrustedEventDeserializer;

/**
 * 출연 이벤트({@code appearance.events.v1}) 소비 설정(설계 문서 5.5, {@code docs/architecture/infrastructure/kafka.md}).
 * 회원 이벤트 소비({@link ChatMemberEventKafkaConfig})와 같은 규칙과 부품을 쓴다.
 * <ul>
 *     <li>그룹 {@code chat-app.appearance-notification}, 시작점 earliest. 신뢰 패키지는 {@value #TRUSTED_PACKAGE}만이다
 *     ({@link TrustedEventDeserializer}).</li>
 *     <li>offset은 리스너가 정상 반환한 뒤(알림 저장 커밋 뒤) 커밋한다.</li>
 *     <li>1초부터 최대 10초까지 지수로 늘리며 3회 블로킹 재시도한 뒤
 *     {@code appearance.events.v1.chat-app.appearance-notification.DLT}로 원본과 같은 partition 번호로 보낸다.
 *     역직렬화 실패·신뢰 밖 타입·검증 실패({@link RejectedEventException})는 바로 보낸다.</li>
 *     <li>DLT 토픽(partition 3, 보존 7일)은 이 앱이 선언한다.</li>
 * </ul>
 * 소비는 기본으로 켜져 있고 {@code hapbang.chat.appearance-events.enabled=false}로 끈다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = ChatAppearanceEventProperties.ENABLED_PROPERTY, matchIfMissing = true)
@EnableConfigurationProperties(ChatAppearanceEventProperties.class)
public class ChatAppearanceEventKafkaConfig {

    public static final String CONTAINER_FACTORY = "chatAppearanceEventListenerContainerFactory";
    static final String TRUSTED_PACKAGE = "com.hapbang.shared.appearance.event";

    @Bean(defaultCandidate = false)
    ConsumerFactory<String, Object> chatAppearanceEventConsumerFactory(KafkaProperties kafkaProperties,
            KafkaConnectionDetails connectionDetails, ChatAppearanceEventProperties properties) {
        return ChatKafkaListenerFactories.consumerFactory(kafkaProperties, connectionDetails, properties,
                TRUSTED_PACKAGE);
    }

    @Bean
    NewTopic chatAppearanceEventDeadLetterTopic(ChatAppearanceEventProperties properties) {
        return ChatDeadLetters.newTopic(properties);
    }

    @Bean(defaultCandidate = false)
    ProducerFactory<String, Object> chatAppearanceEventDeadLetterProducerFactory(KafkaProperties kafkaProperties,
            KafkaConnectionDetails connectionDetails, ChatAppearanceEventProperties properties) {
        return ChatKafkaListenerFactories.deadLetterProducerFactory(kafkaProperties, connectionDetails, properties);
    }

    /** 발행 실패 로그에 값을 남기지 않는 DLT 템플릿 */
    @Bean(defaultCandidate = false)
    KafkaTemplate<String, Object> chatAppearanceEventDeadLetterTemplate(
            @Qualifier("chatAppearanceEventDeadLetterProducerFactory") ProducerFactory<String, Object> producerFactory) {
        return ChatDeadLetters.template(producerFactory);
    }

    @Bean(name = CONTAINER_FACTORY, defaultCandidate = false)
    ConcurrentKafkaListenerContainerFactory<String, Object> chatAppearanceEventListenerContainerFactory(
            @Qualifier("chatAppearanceEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("chatAppearanceEventDeadLetterTemplate") KafkaTemplate<String, Object> deadLetterTemplate,
            ChatAppearanceEventProperties properties) {
        return ChatKafkaListenerFactories.containerFactory(consumerFactory, deadLetterTemplate, properties,
                RejectedEventException.class);
    }
}
