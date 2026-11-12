package com.hapbang.auxiliary.search.in.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

/**
 * 검색 모듈의 회원 이벤트 소비 설정(#771). 챗봇 소비 설정({@code ChatbotKafkaConfig})을 복사했다(#771 Q5). 회원 이벤트는 인플루언서 문서와 강의
 * 판매자 사본이 함께 쓰므로 모듈 공용 위치에 둔다(#882 Q6). 공통 규칙(재시도·DLT·offset)은 {@link SearchKafkaFactories}에 있다.
 * <ul>
 *     <li>같은 앱의 다른 모듈(챗봇·알림)도 Kafka를 받으므로 Boot 기본 컨테이너 팩토리·오류 처리기를 바꾸지 않고 검색 전용으로 만든다
 *     (기본 후보가 아님).</li>
 *     <li>값은 {@code __TypeId__} 헤더의 타입으로 읽고, 신뢰 패키지는 정확히 {@code com.hapbang.shared.member.event}다.
 *     읽을 수 없는 메시지(클래스 없음·형식 오류)는 다시 시도하지 않고 DLT로 보낸다.</li>
 *     <li>토픽 자동 생성은 끈다. 원본 토픽은 회원 앱이, DLT는 여기서 만든다(보존 7일).</li>
 * </ul>
 * {@value #ENABLED}가 꺼지면 뜨지 않는다(기본 켜짐. Kafka가 없는 테스트가 끈다).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = SearchKafkaConfig.ENABLED, matchIfMissing = true)
public class SearchKafkaConfig {

    public static final String ENABLED = "hapbang.search.member-events.enabled";
    public static final String MEMBER_EVENT_SETTINGS = "searchMemberEventSettings";
    public static final String MEMBER_EVENT_CONTAINER_FACTORY = "searchMemberEventListenerContainerFactory";

    static final String DEAD_LETTER_PRODUCER_FACTORY = "searchDeadLetterProducerFactory";

    private final String bootstrapServers;
    private final ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> consumerFactoryCustomizers;
    private final ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers;

    public SearchKafkaConfig(@Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> consumerFactoryCustomizers,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producerFactoryCustomizers) {
        this.bootstrapServers = bootstrapServers;
        this.consumerFactoryCustomizers = consumerFactoryCustomizers;
        this.producerFactoryCustomizers = producerFactoryCustomizers;
    }

    @Bean(name = MEMBER_EVENT_SETTINGS)
    SearchKafkaSettings searchMemberEventSettings() {
        return SearchKafkaSettings.memberDefaults();
    }

    @Bean(defaultCandidate = false)
    ConsumerFactory<String, Object> searchMemberEventConsumerFactory(
            @Qualifier(MEMBER_EVENT_SETTINGS) SearchKafkaSettings settings) {
        JacksonJsonDeserializer<Object> json = new JacksonJsonDeserializer<>();
        json.addTrustedPackages(SearchKafkaSettings.MEMBER_TRUSTED_PACKAGE);
        json.setRemoveTypeHeaders(false);
        return SearchKafkaFactories.consumerFactory(bootstrapServers, settings, new ErrorHandlingDeserializer<>(json),
                consumerFactoryCustomizers);
    }

    @Bean(defaultCandidate = false)
    KafkaTemplate<String, Object> searchMemberEventDeadLetterTemplate() {
        return SearchKafkaFactories.deadLetterTemplate(bootstrapServers, DEAD_LETTER_PRODUCER_FACTORY,
                producerFactoryCustomizers);
    }

    @Bean(defaultCandidate = false)
    DefaultErrorHandler searchMemberEventErrorHandler(
            @Qualifier(MEMBER_EVENT_SETTINGS) SearchKafkaSettings settings,
            @Qualifier("searchMemberEventDeadLetterTemplate") KafkaTemplate<String, Object> template) {
        return SearchKafkaFactories.errorHandler(settings, template);
    }

    @Bean(name = MEMBER_EVENT_CONTAINER_FACTORY, defaultCandidate = false)
    ConcurrentKafkaListenerContainerFactory<String, Object> searchMemberEventListenerContainerFactory(
            @Qualifier("searchMemberEventConsumerFactory") ConsumerFactory<String, Object> consumerFactory,
            @Qualifier("searchMemberEventErrorHandler") DefaultErrorHandler errorHandler,
            ObjectProvider<RecordInterceptor<String, Object>> recordInterceptor) {
        return SearchKafkaFactories.containerFactory(consumerFactory, errorHandler, recordInterceptor);
    }

    @Bean
    NewTopic searchMemberEventDeadLetterTopic(@Qualifier(MEMBER_EVENT_SETTINGS) SearchKafkaSettings settings) {
        return SearchKafkaFactories.deadLetterTopic(settings);
    }
}
