package com.hapbang.chat.out.event;

import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * 채팅 이벤트 발행용 ProducerFactory·KafkaTemplate. Modulith 이벤트 외부화({@link ChatEventExternalizationConfig})가 이 템플릿으로
 * 보낸다. 값은 {@link ChatEventKafkaConstants}에 두고 연결 주소만 {@code spring.kafka.bootstrap-servers}에서 받는다(Kafka 설정은
 * 코드로, 사용자 결정 2026-11-12). {@code spring.kafka.producer.*}·{@code spring.kafka.template.*}는 쓰지 않는다.
 * <ul>
 *     <li>Modulith는 {@code KafkaOperations} 빈을 타입으로 하나 받는다. 소비 쪽 DLT 템플릿은 기본 후보에서 빠져 있으므로
 *     ({@code defaultCandidate = false}) 기본 후보인 이 템플릿 하나만 잡힌다. 이 템플릿이 있으면 Boot 기본 KafkaTemplate은
 *     만들어지지 않는다(chat-app에서 그 템플릿을 쓰는 곳은 없다).</li>
 *     <li>ProducerFactory는 기본 후보에서 빼서 이름으로만 쓴다(Boot의 기본 ProducerFactory를 대신하지 않는다).</li>
 *     <li>본문은 shared 레코드의 JSON이고 {@code __TypeId__} 헤더(레코드 클래스 이름)가 붙는다(kafka.md "발행"). key는 문자열 방 ID다.</li>
 *     <li>Boot가 자기 factory에 적용하는 customizer(Kafka client 지표)를 똑같이 적용한다. 템플릿은 observation을 켜 보내는 메시지에
 *     {@code traceparent}를 싣는다(직접 만든 템플릿에는 {@code spring.kafka.template.observation-enabled}가 적용되지 않는다).</li>
 *     <li>발행 실패 로그에는 key·값을 남기지 않는다(회원 ID·제목이 있다).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ChatEventKafkaConfig {

    public static final String PRODUCER_FACTORY = "chatEventProducerFactory";
    public static final String TEMPLATE = "chatEventKafkaTemplate";

    @Bean(name = PRODUCER_FACTORY, defaultCandidate = false)
    ProducerFactory<String, Object> chatEventProducerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> customizers) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, ChatEventKafkaConstants.PRODUCER_ACKS);
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(props,
                new StringSerializer(), new JacksonJsonSerializer<>());
        customizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    @Bean(name = TEMPLATE)
    KafkaTemplate<String, Object> chatEventKafkaTemplate(
            @Qualifier(PRODUCER_FACTORY) ProducerFactory<String, Object> producerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(producerFactory);
        LoggingProducerListener<String, Object> producerListener = new LoggingProducerListener<>();
        producerListener.setIncludeContents(false);
        template.setProducerListener(producerListener);
        // 빈으로 등록되므로 observation registry는 템플릿이 컨텍스트에서 찾는다
        template.setObservationEnabled(true);
        return template;
    }
}
