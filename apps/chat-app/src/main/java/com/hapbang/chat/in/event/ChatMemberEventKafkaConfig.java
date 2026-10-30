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
 * 회원 이벤트({@code member.events.v1}) 소비 설정(설계 문서 5.1·5.3, {@code docs/architecture/infrastructure/kafka.md}).
 * 운영 정책(재시도·DLT·역직렬화)은 공통 코드 없이 chat-app이 구현한다({@code in.event.kafka}). {@code common/kafka}는 연결 설정만 둔다.
 * <ul>
 *     <li>그룹 {@code chat-app.member-replica}, 시작점 earliest. 역직렬화는 신뢰 패키지 {@value #TRUSTED_PACKAGE}만 믿는
 *     {@link TrustedEventDeserializer}다.</li>
 *     <li>offset: 레코드마다, 리스너가 정상 반환한 뒤(DB 커밋 뒤) 커밋한다.</li>
 *     <li>실패: 1초부터 최대 10초까지 지수로 늘리며 3회 블로킹 재시도한 뒤 {@code member.events.v1.chat-app.member-replica.DLT}로
 *     원본과 같은 partition 번호로 보낸다. 역직렬화 실패·신뢰 밖 타입·검증 실패({@link RejectedEventException})는 재시도 없이 바로
 *     보낸다. DLT 발행 실패는 처리 성공이 아니다.</li>
 *     <li>DLT 토픽(partition 3, 보존 7일)은 이 앱이 {@link NewTopic}으로 선언한다. 만들지 못해도 앱은 뜨고,
 *     {@link ChatMemberEventTopicVerifier}가 로그로 알린다.</li>
 * </ul>
 * 빈은 모두 기본 후보에서 빼서({@code defaultCandidate = false}) Boot의 기본 Kafka 빈이 물러나지 않게 하고, 이름으로만 쓴다.
 * 소비는 기본으로 켜져 있고 {@code hapbang.chat.member-events.enabled=false}로 끈다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = ChatMemberEventProperties.ENABLED_PROPERTY, matchIfMissing = true)
@EnableConfigurationProperties(ChatMemberEventProperties.class)
public class ChatMemberEventKafkaConfig {

    public static final String CONTAINER_FACTORY = "chatMemberEventListenerContainerFactory";
    static final String TRUSTED_PACKAGE = "com.hapbang.shared.member.event";

    @Bean(defaultCandidate = false)
    ConsumerFactory<String, Object> chatMemberEventConsumerFactory(KafkaProperties kafkaProperties,
            KafkaConnectionDetails connectionDetails, ChatMemberEventProperties properties) {
        return ChatKafkaListenerFactories.consumerFactory(kafkaProperties, connectionDetails, properties,
                TRUSTED_PACKAGE);
    }

    /** 이 소비자의 DLT. 이미 있으면 그대로 쓰고(파티션을 줄이지 않는다), 공용 KafkaAdmin이 시작할 때 만든다 */
    @Bean
    NewTopic chatMemberEventDeadLetterTopic(ChatMemberEventProperties properties) {
        return ChatDeadLetters.newTopic(properties);
    }

    @Bean(defaultCandidate = false)
    ProducerFactory<String, Object> chatMemberEventDeadLetterProducerFactory(KafkaProperties kafkaProperties,
            KafkaConnectionDetails connectionDetails, ChatMemberEventProperties properties) {
        return ChatKafkaListenerFactories.deadLetterProducerFactory(kafkaProperties, connectionDetails, properties);
    }

    /** 발행 실패 로그에 값(회원 정보)을 남기지 않는 DLT 템플릿 */
    @Bean(defaultCandidate = false)
    KafkaTemplate<String, Object> chatMemberEventDeadLetterTemplate(
            @Qualifier("chatMemberEventDeadLetterProducerFactory") ProducerFactory<String, Object> producerFactory) {
        return ChatDeadLetters.template(producerFactory);
    }

    @Bean(name = CONTAINER_FACTORY, defaultCandidate = false)
    ConcurrentKafkaListenerContainerFactory<String, Object> chatMemberEventListenerContainerFactory(
            @Qualifier("chatMemberEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("chatMemberEventDeadLetterTemplate") KafkaTemplate<String, Object> deadLetterTemplate,
            ChatMemberEventProperties properties) {
        return ChatKafkaListenerFactories.containerFactory(consumerFactory, deadLetterTemplate, properties,
                RejectedEventException.class);
    }
}
